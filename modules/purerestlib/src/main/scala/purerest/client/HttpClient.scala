// SPDX-License-Identifier: MIT

package purerest.client

import cats.effect.{Async, Resource}
import fs2.io.net.Network
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

/** Builds the underlying http4s `Client[F]` used by purerest's
  * resilient/traced/metered client combinators.
  */
object HttpClient {

  /** An Ember-backed `Client[F]` with default settings. */
  def resource[F[_]: Async: Network]: Resource[F, Client[F]] =
    EmberClientBuilder.default[F].build
}
