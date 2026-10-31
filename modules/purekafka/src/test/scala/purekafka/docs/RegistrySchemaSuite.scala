package purekafka.docs

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

class RegistrySchemaSuite extends FunSuite {

  private def json(s: String): Json = parse(s).fold(throw _, identity)

  test("draft07 swaps $schema, renames $defs and rewrites $refs") {
    val in = json("""{
      "$schema": "http://json-schema.org/draft/2020-12/schema#",
      "type": "object",
      "properties": {"item": {"$ref": "#/$defs/Item"}},
      "$defs": {"Item": {"type": "object", "properties": {"sku": {"type": "string"}}}}
    }""")
    val expected = json("""{
      "$schema": "http://json-schema.org/draft-07/schema#",
      "type": "object",
      "properties": {"item": {"$ref": "#/definitions/Item"}},
      "definitions": {"Item": {"type": "object", "properties": {"sku": {"type": "string"}}}}
    }""")
    assertEquals(RegistrySchema.draft07(in), expected)
  }

  test("draft07 adds $schema when absent") {
    assertEquals(
      RegistrySchema.draft07(json("""{"type": "string"}""")),
      json(
        """{"type": "string", "$schema": "http://json-schema.org/draft-07/schema#"}"""
      )
    )
  }

  test(
    "closed adds additionalProperties false to every object with properties"
  ) {
    val in = json("""{
      "type": "object",
      "properties": {"a": {"type": "object", "properties": {"b": {"type": "string"}}}},
      "definitions": {"D": {"type": "object", "properties": {"c": {"type": "string"}}}}
    }""")
    val out = RegistrySchema.closed(in)
    val c = out.hcursor
    assertEquals(c.get[Boolean]("additionalProperties"), Right(false))
    assertEquals(
      c.downField("properties")
        .downField("a")
        .get[Boolean]("additionalProperties"),
      Right(false)
    )
    assertEquals(
      c.downField("definitions")
        .downField("D")
        .get[Boolean]("additionalProperties"),
      Right(false)
    )
  }

  test("closed keeps an explicit additionalProperties (e.g. a Map's)") {
    val in = json("""{
      "type": "object",
      "properties": {"m": {"type": "object", "properties": {}, "additionalProperties": {"type": "string"}}}
    }""")
    assertEquals(
      RegistrySchema
        .closed(in)
        .hcursor
        .downField("properties")
        .downField("m")
        .downField("additionalProperties")
        .get[String]("type"),
      Right("string")
    )
  }

  test("closed leaves a property named 'properties' alone") {
    val in = json("""{
      "type": "object",
      "properties": {"properties": {"type": "string"}}
    }""")
    assertEquals(
      RegistrySchema
        .closed(in)
        .hcursor
        .downField("properties")
        .downField("properties")
        .keys
        .map(_.toSet),
      Some(Set("type"))
    )
  }
}
