package purekafka.docs

import cats.effect.Async
import org.http4s.HttpRoutes
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location
import org.http4s.implicits._

/** Serves an [[AsyncApiDoc]]-generated document as a plain `HttpRoutes[F]` —
  * the Kafka-side analogue of `purerest.docs.Docs.routes`'s OpenAPI/Swagger
  * serving, but for a static generated document rather than a tapir endpoint
  * list: no `tapir-http4s-server`/`-swagger-ui-bundle` involved, just a fixed
  * route returning the generated JSON.
  */
object Docs {

  /** Real routes serving the generated AsyncAPI document for `channels` as JSON
    * at `/docs/asyncapi.json`, and redirecting `/docs/asyncapi` to
    * `/docs/asyncapi/`: the rendered HTML a service serves under that prefix
    * uses relative `css/`/`js/` paths, which resolve under `/docs/` (and 404)
    * without the trailing slash.
    */
  def routes[F[_]: Async](
      title: String,
      version: String,
      channels: List[AsyncApiChannel[?]]
  ): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._
    val doc = AsyncApiDoc.generate(title, version, channels)
    HttpRoutes.of[F] {
      case GET -> Root / "docs" / "asyncapi.json" =>
        Ok(doc)
      case GET -> Root / "docs" / "asyncapi" =>
        MovedPermanently(Location(uri"/docs/asyncapi/"))
    }
  }
}
