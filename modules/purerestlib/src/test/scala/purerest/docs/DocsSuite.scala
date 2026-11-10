// SPDX-License-Identifier: MIT

package purerest.docs

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import sttp.tapir._
import sttp.tapir.server.ServerEndpoint

class DocsSuite extends CatsEffectSuite {

  private val helloEndpoint = endpoint.get.in("hello").out(stringBody)

  private val helloServerEndpoint: ServerEndpoint[Any, IO] =
    helloEndpoint.serverLogicSuccess[IO](_ => IO.pure("hi"))

  private val routes =
    Docs.routes[IO]("Test API", "1.0", List(helloServerEndpoint))

  test("serves the wrapped endpoint itself") {
    for {
      response <- routes.orNotFound.run(Request[IO](Method.GET, uri"/hello"))
      body <- response.as[String]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body, "hi")
    }
  }

  test("serves a Swagger UI route") {
    for {
      response <- routes.orNotFound.run(Request[IO](Method.GET, uri"/docs/"))
    } yield assertEquals(response.status, Status.Ok)
  }

  test("serves the generated OpenAPI yaml, containing the configured title") {
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/docs.yaml")
      )
      body <- response.as[String]
    } yield {
      assertEquals(response.status, Status.Ok)
      assert(body.contains("Test API"))
    }
  }
}
