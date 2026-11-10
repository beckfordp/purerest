// SPDX-License-Identifier: MIT

package purerest.docs

/** One piece of missing REST documentation, found by [[Docs.undocumented]]. */
sealed trait DocGap {

  /** Human-readable one-liner naming where the gap is, for test failure diffs.
    */
  def render: String
}

object DocGap {

  /** `operation` (e.g. `GET /orders/{id}`) has no `.summary`. */
  final case class MissingSummary(operation: String) extends DocGap {
    def render: String = s"$operation: no summary"
  }

  /** `operation` has no `.description`. */
  final case class MissingOperationDescription(operation: String)
      extends DocGap {
    def render: String = s"$operation: no description"
  }

  /** The `in` (`path`/`query`/`header`) parameter `name` of `operation` has no
    * `.description`.
    */
  final case class MissingParameterDescription(
      operation: String,
      in: String,
      name: String
  ) extends DocGap {
    def render: String =
      s"$operation: $in parameter '$name' has no description"
  }

  /** The non-2xx `status` response of `operation` has no description. */
  final case class MissingResponseDescription(operation: String, status: String)
      extends DocGap {
    def render: String = s"$operation: $status response has no description"
  }

  /** Property `property` of component schema `schema` has no `@description`.
    */
  final case class MissingPropertyDescription(schema: String, property: String)
      extends DocGap {
    def render: String = s"schema $schema.$property: no description"
  }

  /** Request/response body schema `schema` has no example: none on the class,
    * none on the body, and not every field has one.
    */
  final case class MissingExample(schema: String) extends DocGap {
    def render: String =
      s"schema $schema: no example (on the class, the body, or every field)"
  }
}
