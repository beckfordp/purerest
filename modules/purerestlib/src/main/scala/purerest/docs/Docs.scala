package purerest.docs

import cats.effect.Async
import org.http4s.HttpRoutes
import sttp.tapir.AnyEndpoint
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

/** Interprets a service's tapir endpoints into both the real `HttpRoutes[F]`
  * and a generated OpenAPI spec + browsable Swagger UI, from the same source of
  * truth — so routes and docs can't drift apart.
  */
object Docs {

  /** Real routes for `endpoints`, plus a generated OpenAPI spec and Swagger UI.
    */
  def routes[F[_]: Async](
      title: String,
      version: String,
      endpoints: List[ServerEndpoint[Any, F]]
  ): HttpRoutes[F] = {
    val swaggerEndpoints =
      SwaggerInterpreter().fromServerEndpoints[F](endpoints, title, version)
    Http4sServerInterpreter[F]().toRoutes(endpoints ++ swaggerEndpoints)
  }

  /** Documentation gaps in the OpenAPI spec generated for `endpoints` — the
    * spec [[routes]] serves at `/docs` — against the README's "Documenting
    * endpoints" convention: operations without a summary or description,
    * parameters and non-2xx responses without a description, body schema
    * properties without a `@description`, and body schemas without an example.
    * In endpoint order; a fully documented service gets `Nil`, so one test can
    * assert `Docs.undocumented(serverEndpoints.map(_.endpoint)) == Nil`.
    */
  def undocumented(endpoints: List[AnyEndpoint]): List[DocGap] =
    DocsCoverage.gaps(endpoints)
}
