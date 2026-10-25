package purekafka.docs

import cats.effect.{Async, IO}
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.{Method, Request, Status, Uri}
import sttp.apispec.asyncapi.circe._
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

/** Build-time schema-compatibility check/registration against Apicurio Registry
  * (Rework 5 step 2, ADR 0003) - the sbt plugin's `schemaRegistryCheck` task
  * invokes this reflectively, mirroring [[AsyncApiSpecWriter]]'s own
  * entry-point shape exactly (same `main(Array[String])` contract, same
  * `Class.forName(_ + "$")` provider loading), for a dedicated `URLClassLoader`
  * to call across the boundary the sbt plugin controls.
  *
  * Built on `http4s-ember-client` + cats-effect `IO`, not `java.net.http`:
  * `purekafka` already depends on cats-effect/http4s throughout
  * (`StreamSupervisor`/`DeadLetter`/`Resilience`), and every consuming service
  * already carries `http4s-ember-client` transitively via `purerestlib` -
  * nothing new reaches the plugin's isolated classloader. `register` is the
  * directly-testable core (pure `F[_]`, no reflection, no `IO`-running) -
  * `main` is just the thin, reflection-compatible bridge around it.
  */
object SchemaRegistryWriter {

  private val CompatibilityRule: Json =
    Json.obj(
      "ruleType" -> "COMPATIBILITY".asJson,
      "config" -> "BACKWARD".asJson
    )

  private def artifactId(channel: AsyncApiChannel[?]): String =
    s"${channel.topic}-${channel.eventType}"

  private def artifactsUri(registryUrl: String, groupId: String): Uri =
    Uri
      .unsafeFromString(registryUrl)
      .withPath(
        Uri.Path
          .unsafeFromString(s"/apis/registry/v3/groups/$groupId/artifacts")
      )
      .withQueryParam("ifExists", "FIND_OR_CREATE_VERSION")

  private def rulesUri(registryUrl: String, groupId: String, id: String): Uri =
    Uri
      .unsafeFromString(registryUrl)
      .withPath(
        Uri.Path.unsafeFromString(
          s"/apis/registry/v3/groups/$groupId/artifacts/$id/rules"
        )
      )

  /** Registers (or checks) each channel's JSON Schema against Apicurio, setting
    * a `BACKWARD` compatibility rule the first time an artifact is ever
    * created. A non-2xx response from the artifact-registration call (e.g.
    * `400` on an incompatible change) fails `F` - this is the actual
    * enforcement mechanism, not just a logged warning. A `409` on the rule-set
    * call (the rule already exists from an earlier build) is swallowed as
    * success.
    */
  def register[F[_]: Async](
      client: Client[F],
      registryUrl: String,
      groupId: String,
      channels: List[AsyncApiChannel[?]]
  ): F[Unit] =
    channels.traverse_(channel =>
      registerOne(client, registryUrl, groupId, channel)
    )

  private def registerOne[F[_]: Async](
      client: Client[F],
      registryUrl: String,
      groupId: String,
      channel: AsyncApiChannel[?]
  ): F[Unit] = {
    val id = artifactId(channel)
    val payloadSchema =
      TapirSchemaToJsonSchema(channel.schema, markOptionsAsNullable = true)
    val createBody = Json.obj(
      "artifactId" -> id.asJson,
      "artifactType" -> "JSON".asJson,
      "firstVersion" -> Json.obj(
        "content" -> Json.obj(
          "content" -> payloadSchema.asJson.noSpaces.asJson,
          "contentType" -> "application/json".asJson
        )
      )
    )
    val createRequest =
      Request[F](Method.POST, artifactsUri(registryUrl, groupId)).withEntity(
        createBody
      )

    client.run(createRequest).use { response =>
      if (response.status.isSuccess)
        response.as[Json].flatMap { body =>
          val isFirstVersion =
            body.hcursor.downField("version").downField("version").as[String] ==
              Right("1")
          if (isFirstVersion)
            setCompatibilityRule(client, registryUrl, groupId, id)
          else Async[F].unit
        }
      else
        response.as[String].flatMap { errorBody =>
          Async[F].raiseError(
            new RuntimeException(
              s"Schema registration failed for '$id' (${response.status}): $errorBody"
            )
          )
        }
    }
  }

  private def setCompatibilityRule[F[_]: Async](
      client: Client[F],
      registryUrl: String,
      groupId: String,
      id: String
  ): F[Unit] = {
    val request =
      Request[F](Method.POST, rulesUri(registryUrl, groupId, id))
        .withEntity(CompatibilityRule)

    client.run(request).use { response =>
      if (response.status.isSuccess || response.status == Status.Conflict)
        Async[F].unit
      else
        response.as[String].flatMap { errorBody =>
          Async[F].raiseError(
            new RuntimeException(
              s"Setting compatibility rule failed for '$id' (${response.status}): $errorBody"
            )
          )
        }
    }
  }

  /** Reflective entry point the sbt plugin's `schemaRegistryCheck` task
    * invokes, via the same dedicated-`URLClassLoader`-plus-reflection mechanism
    * [[AsyncApiSpecWriter]]'s `asyncApiGenerateSpec` already uses. `args`:
    * provider class name, registry base URL, group id.
    */
  def main(args: Array[String]): Unit = {
    val Array(providerClass, registryUrl, groupId) = args

    val provider = Class
      .forName(providerClass + "$")
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[AsyncApiChannelsProvider]

    EmberClientBuilder
      .default[IO]
      .build
      .use(client =>
        register[IO](client, registryUrl, groupId, provider.channels)
      )
      .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
  }
}
