package fi.pyppe.mauno.slack

import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicReference
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.ISODateTimeFormat
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.XML

object ImakesRssChecker extends LoggerSupport {
  import Utils.backend

  case class RssEntry(id: Int, title: String, time: DateTime, url: String)

  @volatile
  private var latestId: Option[Int] = None

  def findRssEntries(implicit ec: ExecutionContext): Future[List[RssEntry]] = {
    import com.softwaremill.sttp._

    sttp.get(uri"${Config.imakesHost}/atom.xml").response(asByteArray).send().map { res =>
      parseXml(res.unsafeBody)
    }
  }

  private def parseXml(data: Array[Byte]): List[RssEntry] = {
    val xml = XML.load(new ByteArrayInputStream(data))

    val dtf = ISODateTimeFormat.dateTime.withZone(DateTimeZone.UTC)
    (xml \ "entry").map { e =>
      val id = (e \ "id").text.toInt
      val title = (e \ "title").text
      val time = dtf.parseDateTime((e \ "updated").text)
      val url = Config.imakesHost + (e \ "link" \ "@href").text
      RssEntry(id, title, time, url)
    }.toList.sortBy(-_.id)
  }

  object Scheduler {
    val checker: AtomicReference[Option[ScheduledFuture[_]]] = new AtomicReference(None)

    def start(): Unit = {
      import scala.concurrent.ExecutionContext.Implicits.global

      def schedule = Utils.scheduledExecutor("Imakes RSS check", Duration.ofMinutes(1)) {
        val entries = FutureSupport.awaitResultInf(findRssEntries)
        latestId.foreach { previousId =>
          val newEntries = entries.takeWhile(_.id > previousId).take(5)
          if (newEntries.nonEmpty) {
            logger.info(s"Found ${newEntries.size} new entries: ${newEntries.map(_.title).mkString(", ")}")

            newEntries.foreach { e =>
              Slack.sayInGeneralChannel(s"Epäsärkyviä uutisia: ${e.title} ${e.url}")
            }
          }
        }
        entries.headOption.foreach { latest =>
          logger.debug(s"Latest entry: $latest")
          latestId = Some(latest.id)
        }
      }

      if (!checker.compareAndSet(None, Some(schedule))) {
        logger.warn("Already started")
      }
    }

    def shutdown(): Unit = {
      val updater = checker.get
      updater.foreach(_.cancel(true))
      if (checker.compareAndSet(updater, None)) {
        logger.info("Stopped")
      }
    }
  }

  def main(args: Array[String]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    try {
      val res = FutureSupport.awaitResultInf(findRssEntries)
      println(res)
    } catch {
      case err: Throwable =>
        logger.error("Errorrr... ", err)
    } finally {
      Utils.backend.close
    }
  }

}
