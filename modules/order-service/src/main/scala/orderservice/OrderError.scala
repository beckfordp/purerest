// SPDX-License-Identifier: MIT

package orderservice

sealed trait OrderError

case object OrderNotFound extends OrderError
