package purekafka.docs

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Entry point the sbt plugin's `asyncApiGenerateSpec` task (ADR 0010's
  * AsyncAPI follow-up) invokes, via reflection, through a dedicated
  * `URLClassLoader` built from the *consuming* service's own compiled output +
  * dependencies (not `(Compile / runMain)` — that task itself depends on
  * `Compile / compile`, which would make the plugin's "regenerate after every
  * compile" chain circular): given that service's own
  * `AsyncApiChannelsProvider` singleton by class name, generates its AsyncAPI
  * document and writes it to disk. No new `purekafka` dependency for a
  * consuming service beyond what it already needs to build `AsyncApiChannel`
  * values.
  *
  * Reflectively loads the provider class rather than taking a type parameter
  * because this runs across a classloader boundary the plugin controls (a
  * reflective `main` invocation with string args) — there's no compile-time
  * handle to the consuming service's own object from here.
  *
  * No error handling: a wrong class name, a class not implementing
  * [[AsyncApiChannelsProvider]], or an unwritable output path should throw and
  * fail this process loudly, not be swallowed.
  */
object AsyncApiSpecWriter {

  def main(args: Array[String]): Unit = {
    val Array(providerClass, title, version, outputPath) = args

    // Class.forName on the plain name resolves Scala's static-forwarder
    // mirror class, which has no MODULE$ field - the real module class
    // (holding the singleton instance) is always named with a trailing "$".
    val provider = Class
      .forName(providerClass + "$")
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[AsyncApiChannelsProvider]

    val doc = AsyncApiDoc.generate(title, version, provider.channels)

    val path = Paths.get(outputPath)
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.write(path, doc.noSpaces.getBytes(StandardCharsets.UTF_8))
  }
}
