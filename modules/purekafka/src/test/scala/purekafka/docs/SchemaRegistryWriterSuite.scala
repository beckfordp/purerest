package purekafka.docs

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax._
import munit.CatsEffectSuite
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, Request, Response, Status}

class SchemaRegistryWriterSuite extends CatsEffectSuite {

  final case class RecordedRequest(method: String, path: String, body: Json)

  /** A stub `Client[F]` recording every request it receives (for assertions)
    * and returning a canned response per `(method, path)` pair - no real
    * registry needed, mirroring `AsyncApiSpecWriterSuite`'s own fixture-based,
    * no-real-IO testing style for the equivalent writer.
    */
  private def stubClient(
      requestsRef: Ref[IO, List[RecordedRequest]],
      responseFor: (String, String) => (Status, Json)
  ): Client[IO] = {
    val routes = HttpRoutes.of[IO] { case req =>
      req.as[Json].flatMap { body =>
        val method = req.method.name
        val path = req.uri.path.renderString
        requestsRef.update(_ :+ RecordedRequest(method, path, body)) *> {
          val (status, respBody) = responseFor(method, path)
          IO.pure(Response[IO](status).withEntity(respBody))
        }
      }
    }
    Client.fromHttpApp(routes.orNotFound)
  }

  private val oneChannel = List(AsyncApiChannelsProviderFixture.channels.head)

  test(
    "registers one channel's JSON Schema under <topic>-<eventType> and sets a BACKWARD rule on first registration"
  ) {
    for {
      requestsRef <- Ref.of[IO, List[RecordedRequest]](Nil)
      client = stubClient(
        requestsRef,
        (method, path) =>
          (method, path) match {
            case ("POST", "/apis/registry/v3/groups/test-group/artifacts") =>
              (
                Status.Ok,
                Json.obj(
                  "version" -> Json.obj("version" -> "1".asJson)
                )
              )
            case (
                  "POST",
                  "/apis/registry/v3/groups/test-group/artifacts/fixture-topic-FixtureEvent/rules"
                ) =>
              (Status.NoContent, Json.Null)
            case other =>
              fail(s"unexpected request: $other")
          }
      )
      _ <- SchemaRegistryWriter.register[IO](
        client,
        "http://ignored",
        "test-group",
        oneChannel
      )
      requests <- requestsRef.get
    } yield {
      assertEquals(requests.size, 2)
      val createReq = requests(0)
      assertEquals(createReq.method, "POST")
      assertEquals(
        createReq.path,
        "/apis/registry/v3/groups/test-group/artifacts"
      )
      assertEquals(
        createReq.body.hcursor.downField("artifactId").as[String],
        Right("fixture-topic-FixtureEvent")
      )
      assertEquals(
        createReq.body.hcursor.downField("artifactType").as[String],
        Right("JSON")
      )
      assert(
        createReq.body.hcursor
          .downField("firstVersion")
          .downField("content")
          .downField("content")
          .as[String]
          .toOption
          .exists(_.contains("FixturePayload"))
      )

      val ruleReq = requests(1)
      assertEquals(ruleReq.method, "POST")
      assertEquals(
        ruleReq.path,
        "/apis/registry/v3/groups/test-group/artifacts/fixture-topic-FixtureEvent/rules"
      )
      assertEquals(
        ruleReq.body,
        Json.obj(
          "ruleType" -> "COMPATIBILITY".asJson,
          "config" -> "BACKWARD".asJson
        )
      )
    }
  }

  test(
    "does not re-set the rule on a later (non-first) registration of the same artifact"
  ) {
    for {
      requestsRef <- Ref.of[IO, List[RecordedRequest]](Nil)
      client = stubClient(
        requestsRef,
        (method, path) =>
          (method, path) match {
            case ("POST", "/apis/registry/v3/groups/test-group/artifacts") =>
              (
                Status.Ok,
                Json.obj("version" -> Json.obj("version" -> "2".asJson))
              )
            case other => fail(s"unexpected request: $other")
          }
      )
      _ <- SchemaRegistryWriter.register[IO](
        client,
        "http://ignored",
        "test-group",
        oneChannel
      )
      requests <- requestsRef.get
    } yield assertEquals(requests.size, 1)
  }

  test(
    "a 409 on the rule-set call (rule already exists) is swallowed, not an error"
  ) {
    for {
      requestsRef <- Ref.of[IO, List[RecordedRequest]](Nil)
      client = stubClient(
        requestsRef,
        (method, path) =>
          (method, path) match {
            case ("POST", "/apis/registry/v3/groups/test-group/artifacts") =>
              (
                Status.Ok,
                Json.obj("version" -> Json.obj("version" -> "1".asJson))
              )
            case (
                  "POST",
                  "/apis/registry/v3/groups/test-group/artifacts/fixture-topic-FixtureEvent/rules"
                ) =>
              (
                Status.Conflict,
                Json.obj("name" -> "RuleAlreadyExistsException".asJson)
              )
            case other => fail(s"unexpected request: $other")
          }
      )
      result <- SchemaRegistryWriter
        .register[IO](client, "http://ignored", "test-group", oneChannel)
        .attempt
    } yield assert(result.isRight, s"expected success, got $result")
  }

  test(
    "a 400 on the version-registration call (incompatible schema) propagates as a failure"
  ) {
    for {
      requestsRef <- Ref.of[IO, List[RecordedRequest]](Nil)
      client = stubClient(
        requestsRef,
        (method, path) =>
          (method, path) match {
            case ("POST", "/apis/registry/v3/groups/test-group/artifacts") =>
              (
                Status.BadRequest,
                Json.obj(
                  "name" -> "RuleViolationException".asJson,
                  "detail" -> "Incompatible artifact".asJson
                )
              )
            case other => fail(s"unexpected request: $other")
          }
      )
      result <- SchemaRegistryWriter
        .register[IO](client, "http://ignored", "test-group", oneChannel)
        .attempt
    } yield assert(result.isLeft, s"expected a failure, got $result")
  }
}
