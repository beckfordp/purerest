package purekafka.docs

import io.circe.{Json, JsonObject}

/** Pure rewrites applied to a tapir-derived JSON Schema before it reaches
  * Apicurio (gluon TD-18.1).
  *
  * tapir emits draft 2020-12, which Apicurio 3.3.2's compatibility checker
  * can't read (gluon TD-11: any changed schema 500s); [[draft07]] downgrades
  * the only 2020-12-specific parts tapir uses. With the default open content
  * model the checker treats even an optional field addition as "narrowed";
  * [[closed]] makes producer schemas reject unknown properties, so adding an
  * optional field registers as a new BACKWARD-compatible version. Both
  * behaviours were confirmed live against Apicurio 3.3.2 on 2026-10-31.
  */
object RegistrySchema {

  val Draft07: String = "http://json-schema.org/draft-07/schema#"

  /** Sets `$schema` to draft-07, renames top-level `$defs` to `definitions` and
    * rewrites every `#/$defs/` reference to match.
    */
  def draft07(schema: Json): Json =
    schema.asObject.fold(schema) { obj =>
      val renamed = obj("$defs").fold(obj)(defs =>
        JsonObject.fromIterable(obj.toList.map {
          case ("$defs", _) => "definitions" -> defs
          case other        => other
        })
      )
      rewriteRefs(
        Json.fromJsonObject(renamed.add("$schema", Json.fromString(Draft07)))
      )
    }

  private def rewriteRefs(json: Json): Json =
    json.arrayOrObject(
      json,
      arr => Json.fromValues(arr.map(rewriteRefs)),
      obj =>
        Json.fromJsonObject(obj.toIterable.foldLeft(JsonObject.empty) {
          case (acc, ("$ref", ref)) =>
            acc.add(
              "$ref",
              ref.asString.fold(ref)(r =>
                Json.fromString(r.replace("#/$defs/", "#/definitions/"))
              )
            )
          case (acc, (key, value)) => acc.add(key, rewriteRefs(value))
        })
    )

  /** Adds `additionalProperties: false` to every object schema that lists
    * `properties` and doesn't already say otherwise (a `Map`'s explicit
    * `additionalProperties` is kept). Walks subschema positions only, so a
    * field that happens to be named `properties` is never mistaken for one.
    */
  def closed(schema: Json): Json =
    schema.asObject.fold(schema) { obj =>
      val withSubschemas = JsonObject.fromIterable(obj.toList.map {
        case (key @ ("properties" | "definitions" | "$defs"), value) =>
          key -> value.mapObject(_.mapValues(closed))
        case (key @ ("items" | "additionalProperties" | "not"), value) =>
          key -> value.arrayOrObject(
            value,
            arr => Json.fromValues(arr.map(closed)),
            _ => closed(value)
          )
        case (key @ ("anyOf" | "oneOf" | "allOf"), value) =>
          key -> value.mapArray(_.map(closed))
        case other => other
      })
      val isPropertiesObject = obj("properties").exists(_.isObject)
      Json.fromJsonObject(
        if (isPropertiesObject && !obj.contains("additionalProperties"))
          withSubschemas.add("additionalProperties", Json.False)
        else withSubschemas
      )
    }
}
