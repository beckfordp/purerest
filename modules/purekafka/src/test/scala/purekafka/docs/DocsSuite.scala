package purekafka.docs

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import sttp.tapir.Schema

class DocsSuite extends CatsEffectSuite {

  final case class DemoPayload(message: String)
  object DemoPayload {
    implicit val schema: Schema[DemoPayload] = Schema.derived[DemoPayload]
  }

  private val channel = AsyncApiChannel[DemoPayload](
    topic = "kafka-harness-demo",
    eventType = "DemoEvent"
  )

  private val routes =
    Docs.routes[IO]("purekafka demo", "1.0", List(channel))

  test("serves the generated AsyncAPI document as JSON, containing the configured title and channel") {
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/asyncapi.json")
      )
      body <- response.as[String]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(
        response.contentType.map(_.mediaType),
        Some(org.http4s.MediaType.application.json)
      )
      assert(body.contains("purekafka demo"))
      assert(body.contains("kafka-harness-demo"))
    }
  }

  test("404s on an unrelated path") {
    for {
      response <- routes.orNotFound.run(Request[IO](Method.GET, uri"/nope"))
    } yield assertEquals(response.status, Status.NotFound)
  }
}
