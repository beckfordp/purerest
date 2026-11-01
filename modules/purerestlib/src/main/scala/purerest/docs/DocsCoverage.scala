package purerest.docs

import sttp.apispec.{AnySchema, Schema, SchemaLike, SchemaType}
import sttp.apispec.openapi.{
  MediaType,
  OpenAPI,
  Operation,
  PathItem,
  ResponsesCodeKey,
  ResponsesDefaultKey,
  ResponsesKey,
  ResponsesRangeKey
}
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import scala.collection.immutable.ListMap

/** Walks the OpenAPI spec tapir generates for a service's endpoints — the same
  * spec `/docs` serves — and lists what the README's "Documenting endpoints"
  * convention says should be there but isn't.
  */
private[docs] object DocsCoverage {

  private val SchemaRefPrefix = "#/components/schemas/"

  def gaps(endpoints: List[AnyEndpoint]): List[DocGap] = {
    val api = OpenAPIDocsInterpreter().toOpenAPI(endpoints, "", "")
    val schemas =
      api.components.fold(ListMap.empty[String, SchemaLike])(_.schemas)
    val operations = operationsOf(api)
    val bodies = operations.flatMap { case (_, op) => bodyMediaTypes(op) }
    val reachable = reachableSchemas(
      bodies.flatMap(_.schema.toList),
      schemas
    )
    operations.flatMap { case (name, op) => operationGaps(name, op) } ++
      reachable.flatMap(name =>
        schemas.get(name).toList.flatMap(propertyGaps(name, _, schemas))
      ) ++
      exampleGaps(bodies, schemas)
  }

  private def operationsOf(api: OpenAPI): List[(String, Operation)] =
    api.paths.pathItems.toList.flatMap { case (path, item) =>
      methods(item).collect { case (method, Some(op)) =>
        (s"$method $path", op)
      }
    }

  private def methods(item: PathItem): List[(String, Option[Operation])] =
    List(
      "GET" -> item.get,
      "PUT" -> item.put,
      "POST" -> item.post,
      "DELETE" -> item.delete,
      "OPTIONS" -> item.options,
      "HEAD" -> item.head,
      "PATCH" -> item.patch,
      "TRACE" -> item.trace
    )

  private def blank(text: Option[String]): Boolean =
    text.forall(_.trim.isEmpty)

  private def operationGaps(name: String, op: Operation): List[DocGap] = {
    val summary =
      if (blank(op.summary)) List(DocGap.MissingSummary(name)) else Nil
    val description =
      if (blank(op.description)) List(DocGap.MissingOperationDescription(name))
      else Nil
    val parameters = op.parameters.collect {
      case Right(p) if blank(p.description) =>
        DocGap.MissingParameterDescription(name, p.in.value, p.name)
    }
    val responses = op.responses.responses.toList.collect {
      case (key, Right(r)) if !isSuccess(key) && r.description.trim.isEmpty =>
        DocGap.MissingResponseDescription(name, status(key))
    }
    summary ++ description ++ parameters ++ responses
  }

  private def isSuccess(key: ResponsesKey): Boolean = key match {
    case ResponsesCodeKey(code)   => code / 100 == 2
    case ResponsesRangeKey(range) => range == 2
    case ResponsesDefaultKey      => false
  }

  private def status(key: ResponsesKey): String = key match {
    case ResponsesCodeKey(code)   => code.toString
    case ResponsesRangeKey(range) => s"${range}XX"
    case ResponsesDefaultKey      => "default"
  }

  private def bodyMediaTypes(op: Operation): List[MediaType] =
    op.requestBody.toList.collect { case Right(b) =>
      b.content.values
    }.flatten ++
      op.responses.responses.values.toList.collect { case Right(r) =>
        r.content.values
      }.flatten

  private def refName(schema: Schema): Option[String] =
    schema.$ref.collect {
      case ref if ref.startsWith(SchemaRefPrefix) =>
        ref.stripPrefix(SchemaRefPrefix)
    }

  /** Schemas nested directly in `schema`: properties, array items and
    * alternatives.
    */
  private def children(schema: Schema): List[SchemaLike] =
    schema.properties.values.toList ++ schema.items.toList ++
      schema.oneOf ++ schema.anyOf ++ schema.allOf

  /** Component schema names reachable from `roots`, depth-first in first-seen
    * order, so gaps read top-down from each body.
    */
  private def reachableSchemas(
      roots: List[SchemaLike],
      schemas: ListMap[String, SchemaLike]
  ): List[String] =
    roots
      .foldLeft(Vector.empty[String])((seen, root) =>
        visit(root, schemas, seen)
      )
      .toList

  private def visit(
      node: SchemaLike,
      schemas: ListMap[String, SchemaLike],
      seen: Vector[String]
  ): Vector[String] = node match {
    case s: Schema =>
      refName(s) match {
        case Some(name) if seen.contains(name) => seen
        case Some(name)                        =>
          schemas
            .get(name)
            .fold(seen)(target => visit(target, schemas, seen :+ name))
        case None =>
          children(s).foldLeft(seen)((acc, c) => visit(c, schemas, acc))
      }
    case _: AnySchema => seen
  }

  private def propertyGaps(
      name: String,
      schema: SchemaLike,
      schemas: ListMap[String, SchemaLike]
  ): List[DocGap] =
    schema match {
      case s: Schema =>
        s.properties.toList.collect {
          case (property, p) if !described(p, schemas) =>
            DocGap.MissingPropertyDescription(name, property)
        }
      case _: AnySchema => Nil
    }

  /** tapir moves a `@description` on a field of case-class type onto the
    * referenced component (and a class's own onto the `$ref`), so a `$ref`
    * property counts as described when either side is.
    */
  private def described(
      property: SchemaLike,
      schemas: ListMap[String, SchemaLike]
  ): Boolean = property match {
    case p: Schema =>
      !blank(p.description) || refName(p).flatMap(schemas.get).exists {
        case target: Schema => !blank(target.description)
        case _: AnySchema   => false
      }
    case _: AnySchema => false
  }

  private def exampleGaps(
      bodies: List[MediaType],
      schemas: ListMap[String, SchemaLike]
  ): List[DocGap] =
    bodies
      .filter(m => m.example.isEmpty && m.examples.isEmpty)
      .flatMap(_.schema.toList.flatMap(bodySchemaName))
      .distinct
      .filterNot(name =>
        schemas.get(name).exists(exemplified(_, schemas, Set(name)))
      )
      .map(DocGap.MissingExample(_))

  /** The component a body refers to, directly or as an array's items. */
  private def bodySchemaName(schema: SchemaLike): Option[String] =
    schema match {
      case s: Schema =>
        refName(s).orElse(s.items.flatMap(bodySchemaName))
      case _: AnySchema => None
    }

  /** Whether Swagger can show a full example for `node`: one is given on it, it
    * is an enum, or everything inside it is exemplified.
    */
  private def exemplified(
      node: SchemaLike,
      schemas: ListMap[String, SchemaLike],
      seen: Set[String]
  ): Boolean = node match {
    case s: Schema if s.examples.exists(_.nonEmpty)            => true
    case s: Schema if s.`type`.contains(List(SchemaType.Null)) => true
    // Swagger UI shows an enum's first value (or a const) as its example.
    case s: Schema if s.`enum`.exists(_.nonEmpty) || s.const.isDefined => true
    case s: Schema                                                     =>
      refName(s) match {
        case Some(name) if seen.contains(name) => true
        case Some(name)                        =>
          schemas.get(name).exists(exemplified(_, schemas, seen + name))
        case None =>
          val nested = children(s)
          nested.nonEmpty && nested.forall(exemplified(_, schemas, seen))
      }
    case _: AnySchema => false
  }
}
