// SPDX-License-Identifier: MIT

package purerest.client

import cats.effect.IO
import com.comcast.ip4s._
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Response, Status, Uri}
import org.http4s.ember.server.EmberServerBuilder

class HttpClientSuite extends CatsEffectSuite {

  test("resource can be acquired and released") {
    HttpClient.resource[IO].use(_ => IO.unit)
  }

  test("resource can make a request against a stub route") {
    val stubApp: HttpApp[IO] = HttpApp { _ =>
      IO.pure(Response[IO](Status.Ok).withEntity("pong"))
    }

    val serverResource = EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0")
      .withHttpApp(stubApp)
      .build

    serverResource.use { server =>
      HttpClient.resource[IO].use { client =>
        val uri = Uri.unsafeFromString(
          s"http://127.0.0.1:${server.address.getPort}/ping"
        )
        client.expect[String](uri).map(body => assertEquals(body, "pong"))
      }
    }
  }
}
