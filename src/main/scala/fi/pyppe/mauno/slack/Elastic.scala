package fi.pyppe.mauno.slack

import io.circe.{Encoder, Json}
import io.circe.syntax._
import org.joda.time.DateTime
import scala.concurrent.{ExecutionContext, Future}

case class IndexedMessage(time: DateTime,
                          nickname: String,
                          username: String,
                          text: String,
                          links: List[String])
object IndexedMessage {
  implicit val encoder = new Encoder[IndexedMessage] {
    final def apply(m: IndexedMessage) = Json.obj(
      "time" := m.time.getMillis,
      "nickname" := m.nickname,
      "username" := m.username,
      "text" := m.text,
      "links" := m.links,
      "linkCount" := m.links.size
    )
  }

  def create(nick: String, text: String) = {
    IndexedMessage(
      DateTime.now,
      nick,
      nick,
      text,
      parseUrls(text)
    )
  }

  def parseUrls(text: String): List[String] =
    text.split("\\s+").map(_.trim.replaceAll("^\\<(.+)\\>$", "$1")).filter(_.matches("(?i)^(ftp|https?)://.+")).
      map(_.replaceAll("(.*)[,!.:?()<>]$", "$1")).toList
}

object Elastic extends LoggerSupport {
  import com.softwaremill.sttp._
  import com.softwaremill.sttp.circe._
  import Utils.backend

  def save(msg: IndexedMessage)(implicit ec: ExecutionContext): Future[Unit] = {

    logger.debug(s"Save $msg")

    val async = sttp.post(uri"${Config.elasticHost}/ircbot/message").
      body(msg).
      send.
      map { resp =>
        require(resp.isSuccess, s"Invalid response (${resp.code}): ${resp.unsafeBody}")
      }

    async.failed.foreach { err =>
      logger.error(s"Error indexing $msg", err)
    }

    async
  }

}
