package fi.pyppe.mauno.slack

import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration.Duration

object FutureSupport {

  def awaitResultInf[T](awaitable: Awaitable[T]): T = {
    Await.result(awaitable, Duration.Inf)
  }

}
