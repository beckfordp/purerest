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

  final case class RecordedRequest(
      method: String,
      path: String,
      query: String,
      body: Json
  )

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
        requestsRef.update(
          _ :+ RecordedRequest(method, path, req.uri.query.renderString, body)
        ) *> {
          val (status, respBody) = responseFor(method, path)
          IO.pure(Response[IO](status).withEntity(respBody))
        }
      }
    }
    Client.fromHttpApp(routes.orNotFound)
  }

  private val oneChannel = List(AsyncApiChannelsProviderFixture.channels.head)

  test(
    "registers a producer channel's closed draft-07 schema under <topic>-<eventType>, labelled with its producer, and sets BACKWARD_TRANSITIVE on first registration"
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
        "test-producer",
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
      val registered = createReq.body.hcursor
        .downField("firstVersion")
        .downField("content")
        .downField("content")
        .as[String]
        .flatMap(io.circe.parser.parse)
        .toOption
        .get
      assertEquals(
        registered.hcursor.get[String]("$schema"),
        Right(RegistrySchema.Draft07)
      )
      assertEquals(
        registered.hcursor.get[Boolean]("additionalProperties"),
        Right(false)
      )
      assertEquals(
        createReq.body.hcursor.downField("labels").get[String]("producer"),
        Right("test-producer")
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
          "config" -> "BACKWARD_TRANSITIVE".asJson
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
        "test-producer",
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
        .register[IO](
          client,
          "http://ignored",
          "test-group",
          "test-producer",
          oneChannel
        )
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
        .register[IO](
          client,
          "http://ignored",
          "test-group",
          "test-producer",
          oneChannel
        )
        .attempt
    } yield assert(result.isLeft, s"expected a failure, got $result")
  }

  private val subscribeChannel = List(
    AsyncApiChannel[FixturePayload](
      topic = "fixture-topic",
      eventType = "FixtureEvent",
      direction = ChannelDirection.Subscribe
    )
  )

  private val dryRunPath =
    "/apis/registry/v3/groups/test-group/artifacts/fixture-topic-FixtureEvent/versions"

  private def runSubscribe(status: Status) =
    for {
      requestsRef <- Ref.of[IO, List[RecordedRequest]](Nil)
      client = stubClient(
        requestsRef,
        (method, path) =>
          (method, path) match {
            case ("POST", `dryRunPath`) => (status, Json.obj())
            case other                  => fail(s"unexpected request: $other")
          }
      )
      result <- SchemaRegistryWriter
        .register[IO](
          client,
          "http://ignored",
          "test-group",
          "test-consumer",
          subscribeChannel
        )
        .attempt
      requests <- requestsRef.get
    } yield (result, requests)

  test(
    "a subscribe channel only dry-runs its open draft-07 mirror against the producer's artifact"
  ) {
    runSubscribe(Status.Ok).map { case (result, requests) =>
      assert(result.isRight, s"expected success, got $result")
      assertEquals(requests.size, 1)
      assertEquals(requests.head.query, "dryRun=true")
      val mirror = requests.head.body.hcursor
        .downField("content")
        .get[String]("content")
        .flatMap(io.circe.parser.parse)
        .toOption
        .get
      assertEquals(
        mirror.hcursor.get[String]("$schema"),
        Right(RegistrySchema.Draft07)
      )
      assert(!mirror.hcursor.downField("additionalProperties").succeeded)
    }
  }

  test("a subscribe channel whose producer artifact is absent is skipped") {
    runSubscribe(Status.NotFound).map { case (result, _) =>
      assert(result.isRight, s"expected success, got $result")
    }
  }

  test("an incompatible subscribe mirror (Apicurio 400 RuleViolation) fails") {
    runSubscribe(Status.BadRequest).map { case (result, _) =>
      assert(result.isLeft, s"expected a failure, got $result")
    }
  }
}
