// SPDX-License-Identifier: MIT

package purekafka.sbtplugin

/** A Node.js version as `node -v` prints it, e.g. `v26.13.0`. Lets
  * `AsyncApiHtmlGenerator` skip, with a clear warning, on a Node too old for
  * `@asyncapi/generator` instead of failing somewhere inside npm.
  */
final case class NodeVersion(major: Int, minor: Int, patch: Int) {

  def atLeast(other: NodeVersion): Boolean =
    Ordering[(Int, Int, Int)].gteq(
      (major, minor, patch),
      (other.major, other.minor, other.patch)
    )

  override def toString: String = s"v$major.$minor.$patch"
}

object NodeVersion {

  private val Pattern = """v?(\d+)\.(\d+)\.(\d+)""".r

  /** `None` for anything that isn't `[v]major.minor.patch` (e.g. a shell's
    * "command not found"), so callers treat it like a missing Node.
    */
  def parse(output: String): Option[NodeVersion] =
    output.trim match {
      case Pattern(major, minor, patch) =>
        Some(NodeVersion(major.toInt, minor.toInt, patch.toInt))
      case _ => None
    }
}
