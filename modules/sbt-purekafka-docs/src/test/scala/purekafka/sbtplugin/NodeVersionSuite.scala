// SPDX-License-Identifier: MIT

package purekafka.sbtplugin

import munit.FunSuite

class NodeVersionSuite extends FunSuite {

  test("parses node -v output, with or without the v prefix and whitespace") {
    assertEquals(NodeVersion.parse("v26.13.0\n"), Some(NodeVersion(26, 13, 0)))
    assertEquals(NodeVersion.parse("24.11.1"), Some(NodeVersion(24, 11, 1)))
  }

  test("rejects output that isn't a version") {
    assertEquals(NodeVersion.parse(""), None)
    assertEquals(NodeVersion.parse("command not found: node"), None)
    assertEquals(NodeVersion.parse("v26"), None)
  }

  test("compares major, then minor, then patch") {
    val min = NodeVersion(24, 11, 0)
    assert(NodeVersion(24, 11, 0).atLeast(min))
    assert(NodeVersion(24, 22, 2).atLeast(min))
    assert(NodeVersion(26, 0, 0).atLeast(min))
    assert(!NodeVersion(24, 10, 9).atLeast(min))
    assert(!NodeVersion(18, 16, 0).atLeast(min))
  }

  test("renders as node -v does") {
    assertEquals(NodeVersion(26, 13, 0).toString, "v26.13.0")
  }
}
