// SPDX-License-Identifier: MIT

package purerest.logging

import cats.effect.Sync
import cats.syntax.all._
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.trace.Tracer

/** Structured, trace-correlated logging built on log4cats. */
object Logging {

  /** A trace-correlated structured logger backed by SLF4J, for
    * `Main`/production use.
    */
  def create[F[_]: Sync](
      tracer: Tracer[F],
      name: String
  ): F[StructuredLogger[F]] =
    Slf4jLogger.fromName[F](name).map(traceCorrelated(tracer, _))

  /** Wraps `underlying` so every log call's context is stamped with the current
    * span's trace id and span id, if a span is active. Generic over the
    * underlying logger so it's directly testable with any
    * `StructuredLogger[F]`.
    */
  def traceCorrelated[F[_]: Sync](
      tracer: Tracer[F],
      underlying: StructuredLogger[F]
  ): StructuredLogger[F] =
    new StructuredLogger[F] {
      private def withTrace(ctx: Map[String, String]): F[Map[String, String]] =
        tracer.currentSpanContext.map {
          case Some(sc) =>
            ctx ++ Map("trace_id" -> sc.traceIdHex, "span_id" -> sc.spanIdHex)
          case None => ctx
        }

      def trace(message: => String): F[Unit] =
        withTrace(Map.empty).flatMap(underlying.trace(_)(message))
      def trace(t: Throwable)(message: => String): F[Unit] =
        withTrace(Map.empty).flatMap(underlying.trace(_, t)(message))
      def trace(ctx: Map[String, String])(message: => String): F[Unit] =
        withTrace(ctx).flatMap(underlying.trace(_)(message))
      def trace(ctx: Map[String, String], t: Throwable)(
          message: => String
      ): F[Unit] =
        withTrace(ctx).flatMap(underlying.trace(_, t)(message))

      def debug(message: => String): F[Unit] =
        withTrace(Map.empty).flatMap(underlying.debug(_)(message))
      def debug(t: Throwable)(message: => String): F[Unit] =
        withTrace(Map.empty).flatMap(underlying.debug(_, t)(message))
      def debug(ctx: Map[String, String])(message: => String): F[Unit] =
        withTrace(ctx).flatMap(underlying.debug(_)(message))
      def debug(ctx: Map[String, String], t: Throwable)(
          message: => String
      ): F[Unit] =
        withTrace(ctx).flatMap(underlying.debug(_, t)(message))

      def info(message: => String): F[Unit] =
        withTrace(Map.empty).flatMap(underlying.info(_)(message))
      def info(t: Throwable)(message: => String): F[Unit] =
        withTrace(Map.empty).flatMap(underlying.info(_, t)(message))
      def info(ctx: Map[String, String])(message: => String): F[Unit] =
        withTrace(ctx).flatMap(underlying.info(_)(message))
      def info(ctx: Map[String, String], t: Throwable)(
          message: => String
      ): F[Unit] =
        withTrace(ctx).flatMap(underlying.info(_, t)(message))

      def warn(message: => String): F[Unit] =
        withTrace(Map.empty).flatMap(underlying.warn(_)(message))
      def warn(t: Throwable)(message: => String): F[Unit] =
        withTrace(Map.empty).flatMap(underlying.warn(_, t)(message))
      def warn(ctx: Map[String, String])(message: => String): F[Unit] =
        withTrace(ctx).flatMap(underlying.warn(_)(message))
      def warn(ctx: Map[String, String], t: Throwable)(
          message: => String
      ): F[Unit] =
        withTrace(ctx).flatMap(underlying.warn(_, t)(message))

      def error(message: => String): F[Unit] =
        withTrace(Map.empty).flatMap(underlying.error(_)(message))
      def error(t: Throwable)(message: => String): F[Unit] =
        withTrace(Map.empty).flatMap(underlying.error(_, t)(message))
      def error(ctx: Map[String, String])(message: => String): F[Unit] =
        withTrace(ctx).flatMap(underlying.error(_)(message))
      def error(ctx: Map[String, String], t: Throwable)(
          message: => String
      ): F[Unit] =
        withTrace(ctx).flatMap(underlying.error(_, t)(message))
    }
}
