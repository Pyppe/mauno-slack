package fi.pyppe.mauno.slack

import com.softwaremill.sttp.asynchttpclient.future.AsyncHttpClientFutureBackend
import java.time.Duration
import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}
import org.apache.commons.text.StringEscapeUtils
import scala.util.Random

object Utils extends LoggerSupport {

  implicit val backend = AsyncHttpClientFutureBackend()

  def singleThreadExecutor(name: String) = Executors.newSingleThreadScheduledExecutor { runnable =>
    val t = new Thread(runnable, name)
    t.setDaemon(true)
    t
  }

  def scheduledExecutor(name: String, interval: Duration)
                       (block: => Unit): ScheduledFuture[_] = {

    val executor = singleThreadExecutor(name)

    val task = new Runnable() {
      override def run() = {
        try {
          block
        } catch {
          case err: Throwable => logger.error(s"Exception in $name", err)
        }
      }
    }

    val firstDelay = 500L + Random.nextInt(2000)

    executor.scheduleAtFixedRate(task, firstDelay, interval.toMillis, TimeUnit.MILLISECONDS)
  }

  def unescapeHtml(text: String) = StringEscapeUtils.unescapeHtml4(text)

}
