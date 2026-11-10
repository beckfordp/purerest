// SPDX-License-Identifier: MIT

package purerest.logging

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.testing.StructuredTestingLogger
import purerest.tracing.Tracing

class LoggingSuite extends CatsEffectSuite {

  test(
    "log lines include the current trace id and span id when inside a span"
  ) {
    Tracing.test[IO]("purerest-logging-test").use { testTracer =>
      val testLogger = StructuredTestingLogger.impl[IO]()
      val logger = Logging.traceCorrelated(testTracer.tracer, testLogger)

      for {
        _ <- testTracer.tracer
          .span("logged-operation")
          .use(_ => logger.info("hello"))
        logged <- testLogger.logged
      } yield {
        assertEquals(logged.length, 1)
        assertEquals(logged.head.message, "hello")
        assert(logged.head.ctx.contains("trace_id"))
        assert(logged.head.ctx.contains("span_id"))
      }
    }
  }

  test("log lines have no trace/span id context when no span is active") {
    Tracing.test[IO]("purerest-logging-test").use { testTracer =>
      val testLogger = StructuredTestingLogger.impl[IO]()
      val logger = Logging.traceCorrelated(testTracer.tracer, testLogger)

      for {
        _ <- logger.info("no span here")
        logged <- testLogger.logged
      } yield {
        assertEquals(logged.length, 1)
        assert(!logged.head.ctx.contains("trace_id"))
        assert(!logged.head.ctx.contains("span_id"))
      }
    }
  }
}
