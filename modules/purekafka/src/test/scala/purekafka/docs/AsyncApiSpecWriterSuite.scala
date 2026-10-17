package purekafka.docs

import io.circe.parser.parse
import munit.FunSuite

import java.nio.file.Files

class AsyncApiSpecWriterSuite extends FunSuite {

  test(
    "writes valid AsyncAPI JSON to the output path, matching AsyncApiDoc.generate's own output for that provider's channels"
  ) {
    val outputPath = Files.createTempFile("asyncapi-spec-writer-test", ".json")
    outputPath.toFile.deleteOnExit()

    AsyncApiSpecWriter.main(
      Array(
        "purekafka.docs.AsyncApiChannelsProviderFixture",
        "fixture title",
        "1.0",
        outputPath.toString
      )
    )

    val written = parse(Files.readString(outputPath)).toOption.get
    val expected = AsyncApiDoc.generate(
      "fixture title",
      "1.0",
      AsyncApiChannelsProviderFixture.channels
    )

    assertEquals(written, expected)
  }
}
