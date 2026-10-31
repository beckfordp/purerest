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
      "config" -> "BACKWARD_TRANSITIVE".asJson
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

  private def dryRunUri(registryUrl: String, groupId: String, id: String): Uri =
    Uri
      .unsafeFromString(registryUrl)
      .withPath(
        Uri.Path.unsafeFromString(
          s"/apis/registry/v3/groups/$groupId/artifacts/$id/versions"
        )
      )
      .withQueryParam("dryRun", "true")

  private def contentJson(schema: Json): Json =
    Json.obj(
      "content" -> schema.noSpaces.asJson,
      "contentType" -> "application/json".asJson
    )

  private def tapirSchema(channel: AsyncApiChannel[?]): Json =
    TapirSchemaToJsonSchema(channel.schema, markOptionsAsNullable = true).asJson

  private def rulesUri(registryUrl: String, groupId: String, id: String): Uri =
    Uri
      .unsafeFromString(registryUrl)
      .withPath(
        Uri.Path.unsafeFromString(
          s"/apis/registry/v3/groups/$groupId/artifacts/$id/rules"
        )
      )

  /** Producer-only registration (gluon TD-18.2). A channel that publishes
    * (`Publish`/`Both`) registers its closed draft-07 schema (see
    * [[RegistrySchema]]) as a new version, labelled `producer=<producer>`,
    * setting a `BACKWARD_TRANSITIVE` rule when the artifact is first created. A
    * `Subscribe` channel never writes: it dry-runs its (open) mirror against
    * the producer's artifact, warning and skipping if that artifact doesn't
    * exist yet. Any other non-2xx (e.g. an incompatible change) fails `F` - the
    * enforcement mechanism. A `409` on the rule-set call (rule already exists)
    * is swallowed as success.
    */
  def register[F[_]: Async](
      client: Client[F],
      registryUrl: String,
      groupId: String,
      producer: String,
      channels: List[AsyncApiChannel[?]]
  ): F[Unit] =
    channels.traverse_(channel =>
      channel.direction match {
        case ChannelDirection.Subscribe =>
          dryRunMirror(client, registryUrl, groupId, channel)
        case _ =>
          registerOne(client, registryUrl, groupId, producer, channel)
      }
    )

  private def dryRunMirror[F[_]: Async](
      client: Client[F],
      registryUrl: String,
      groupId: String,
      channel: AsyncApiChannel[?]
  ): F[Unit] = {
    val id = artifactId(channel)
    val mirror = RegistrySchema.draft07(tapirSchema(channel))
    val request =
      Request[F](Method.POST, dryRunUri(registryUrl, groupId, id))
        .withEntity(Json.obj("content" -> contentJson(mirror)))

    client.run(request).use { response =>
      if (response.status.isSuccess) Async[F].unit
      else if (response.status == Status.NotFound)
        Async[F].delay(
          Console.err.println(
            s"[warn] No producer artifact '$id' in group '$groupId' yet; skipped consumer mirror check"
          )
        )
      else
        response.as[String].flatMap { errorBody =>
          Async[F].raiseError(
            new RuntimeException(
              s"Consumer mirror of '$id' is incompatible with the producer's schema (${response.status}): $errorBody"
            )
          )
        }
    }
  }

  private def registerOne[F[_]: Async](
      client: Client[F],
      registryUrl: String,
      groupId: String,
      producer: String,
      channel: AsyncApiChannel[?]
  ): F[Unit] = {
    val id = artifactId(channel)
    val payloadSchema =
      RegistrySchema.closed(RegistrySchema.draft07(tapirSchema(channel)))
    val createBody = Json.obj(
      "artifactId" -> id.asJson,
      "artifactType" -> "JSON".asJson,
      "labels" -> Json.obj("producer" -> producer.asJson),
      "firstVersion" -> Json.obj("content" -> contentJson(payloadSchema))
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
    * provider class name, registry base URL, group id, producer (the service
    * name; defaults to the group id for pre-0.5 plugins).
    */
  def main(args: Array[String]): Unit = {
    val (providerClass, registryUrl, groupId, producer) = args match {
      case Array(p, u, g, s) => (p, u, g, s)
      case Array(p, u, g)    => (p, u, g, g)
      case other             =>
        throw new IllegalArgumentException(
          s"expected 3 or 4 args, got ${other.length}"
        )
    }

    val provider = Class
      .forName(providerClass + "$")
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[AsyncApiChannelsProvider]

    EmberClientBuilder
      .default[IO]
      .build
      .use(client =>
        register[IO](client, registryUrl, groupId, producer, provider.channels)
      )
      .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
  }
}
