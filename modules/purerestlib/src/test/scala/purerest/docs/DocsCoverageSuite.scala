package purerest.docs

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import munit.FunSuite
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.Schema.annotations.{description, encodedExample}
import sttp.tapir.json.circe._

import DocsCoverageSuite._

class DocsCoverageSuite extends FunSuite {

  private def gaps(endpoints: AnyEndpoint*): List[DocGap] =
    Docs.undocumented(endpoints.toList)

  test("a fully documented service has no gaps") {
    assertEquals(gaps(getOrder, placeOrder), Nil)
  }

  test("reports an operation without a summary") {
    assertEquals(
      gaps(endpoint.get.in("ping").description("Liveness.")),
      List(DocGap.MissingSummary("GET /ping"))
    )
  }

  test("reports an operation without a description") {
    assertEquals(
      gaps(endpoint.get.in("ping").summary("Ping")),
      List(DocGap.MissingOperationDescription("GET /ping"))
    )
  }

  test("reports path, query and header parameters without a description") {
    val bare = described(
      endpoint.get
        .in("pings" / path[String]("id"))
        .in(query[Option[Int]]("limit"))
        .in(header[Option[String]]("X-Request-Id"))
    )
    assertEquals(
      gaps(bare),
      List(
        DocGap.MissingParameterDescription("GET /pings/{id}", "path", "id"),
        DocGap.MissingParameterDescription("GET /pings/{id}", "query", "limit"),
        DocGap.MissingParameterDescription(
          "GET /pings/{id}",
          "header",
          "X-Request-Id"
        )
      )
    )
  }

  test("reports an error response without a description") {
    val bare =
      described(
        endpoint.get.in("ping").errorOut(statusCode(StatusCode.NotFound))
      )
    assertEquals(
      gaps(bare),
      List(DocGap.MissingResponseDescription("GET /ping", "404"))
    )
  }

  test("reports schema properties without a description, nested ones too") {
    val bare = described(endpoint.get.in("ping").out(jsonBody[Outer]))
    assertEquals(
      gaps(bare),
      List(
        DocGap.MissingPropertyDescription("Outer", "name"),
        DocGap.MissingPropertyDescription("Inner", "code")
      )
    )
  }

  test("reports a body schema without an example") {
    val bare = described(endpoint.post.in("ping").in(jsonBody[NoExample]))
    assertEquals(gaps(bare), List(DocGap.MissingExample("NoExample")))
  }

  test("reports the item schema of an array body without an example") {
    val bare = described(endpoint.get.in("ping").out(jsonBody[List[NoExample]]))
    assertEquals(gaps(bare), List(DocGap.MissingExample("NoExample")))
  }

  test("accepts a body example given on the endpoint instead of the schema") {
    val exemplified = described(
      endpoint.post
        .in("ping")
        .in(jsonBody[NoExample].example(NoExample("hello")))
    )
    assertEquals(gaps(exemplified), Nil)
  }

  test("reports gaps in endpoint order, with readable renderings") {
    val rendered = gaps(
      endpoint.get.in("b").description("B."),
      endpoint.get.in("a").summary("A")
    ).map(_.render)
    assertEquals(
      rendered,
      List("GET /b: no summary", "GET /a: no description")
    )
  }

  test("renders every gap kind") {
    assertEquals(
      List(
        DocGap.MissingParameterDescription("GET /p/{id}", "path", "id"),
        DocGap.MissingResponseDescription("GET /p", "404"),
        DocGap.MissingPropertyDescription("Order", "status"),
        DocGap.MissingExample("Order")
      ).map(_.render),
      List(
        "GET /p/{id}: path parameter 'id' has no description",
        "GET /p: 404 response has no description",
        "schema Order.status: no description",
        "schema Order: no example (on the class, the body, or every field)"
      )
    )
  }
}

object DocsCoverageSuite {

  private def described[A, I, E, O, R](
      e: Endpoint[A, I, E, O, R]
  ): Endpoint[A, I, E, O, R] =
    e.summary("Ping").description("Ping endpoint.")

  @description("One line of an order.")
  final case class Line(
      @description("Stock-keeping unit.") @encodedExample("SKU-1")
      sku: String,
      @description("Units ordered.") @encodedExample(2)
      quantity: Int
  )
  object Line {
    implicit val codec: Codec[Line] = deriveCodec
    implicit val schema: Schema[Line] = Schema.derived
  }

  @description("Request to place an order.")
  final case class PlaceOrder(
      @description("Customer placing the order.") @encodedExample("cust-1")
      customerId: String,
      @description("The single line ordered.")
      line: Line
  )
  object PlaceOrder {
    implicit val codec: Codec[PlaceOrder] = deriveCodec
    implicit val schema: Schema[PlaceOrder] = Schema.derived
  }

  @description("An order as stored.")
  final case class OrderView(
      @description("Order id.") @encodedExample("ord-1")
      id: String,
      @description("Lines ordered.")
      lines: List[Line]
  )
  object OrderView {
    implicit val codec: Codec[OrderView] = deriveCodec
    implicit val schema: Schema[OrderView] = Schema.derived
  }

  @description("No order has the requested id.")
  final case class OrderNotFound(
      @description("The id looked up.") @encodedExample("ord-404")
      id: String
  )
  object OrderNotFound {
    implicit val codec: Codec[OrderNotFound] = deriveCodec
    implicit val schema: Schema[OrderNotFound] = Schema.derived
  }

  final case class Inner(@encodedExample("X1") code: String)
  object Inner {
    implicit val codec: Codec[Inner] = deriveCodec
    implicit val schema: Schema[Inner] = Schema.derived
  }

  final case class Outer(
      @encodedExample("n") name: String,
      @description("Nested part.") inner: Inner
  )
  object Outer {
    implicit val codec: Codec[Outer] = deriveCodec
    implicit val schema: Schema[Outer] = Schema.derived
  }

  final case class NoExample(@description("Some text.") text: String)
  object NoExample {
    implicit val codec: Codec[NoExample] = deriveCodec
    implicit val schema: Schema[NoExample] = Schema.derived
  }

  val getOrder: AnyEndpoint =
    endpoint.get
      .in(
        "orders" / path[String]("id").description("Order id.").example("ord-1")
      )
      .in(query[Option[Int]]("limit").description("Max lines returned."))
      .in(header[Option[String]]("X-Request-Id").description("Correlation id."))
      .errorOut(
        statusCode(StatusCode.NotFound)
          .and(jsonBody[OrderNotFound].description("No order with this id."))
      )
      .out(jsonBody[OrderView])
      .summary("Get an order")
      .description("Returns the order with its lines. Read-only.")
      .tag("orders")

  val placeOrder: AnyEndpoint =
    endpoint.post
      .in("orders")
      .in(jsonBody[PlaceOrder])
      .out(jsonBody[OrderView])
      .summary("Place an order")
      .description("Creates an order for one line. Not idempotent.")
      .tag("orders")
}
