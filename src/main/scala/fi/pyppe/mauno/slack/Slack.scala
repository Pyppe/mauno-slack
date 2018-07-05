package fi.pyppe.mauno.slack

import akka.actor.ActorSystem
import com.softwaremill.sttp.asynchttpclient.future.AsyncHttpClientFutureBackend
import org.joda.time.DateTime
import scala.concurrent.{ExecutionContext, Future}
import slack.SlackUtil
import slack.api.SlackApiClient
import slack.models.MessageSubtypes.FileShareMessage
import slack.models.{Message, MessageWithSubtype, User, UserTyping}
import slack.rtm.SlackRtmClient

object Slack extends LoggerSupport {

  def props(key: String) = Option(System.getProperty(key)).getOrElse {
    throw new Exception(s"System property <$key> not found")
  }
  val GeneralChannelId = "C0QAPKH36"
  val PyppeUserId = "U0XSM1QN6" // pyppe
  val ProxyUserId = "UAY6VSAE8" // Manolito

  private val SayCommand = """!say +(.+)""".r

  implicit val system = ActorSystem("slack")
  implicit val ec = system.dispatcher

  val rtmClient = SlackRtmClient(Config.slackBotToken)
  val botId = rtmClient.state.self.id
  val apiClient = SlackApiClient(Config.slackBotToken)

  def registerSlackGateway() = {
    rtmClient.onEvent {
      case msg: Message =>
        if (SlackUtil.isDirectMsg(msg)) {
          msg.text match {
            case SayCommand(say) if say.trim.nonEmpty =>
              rtmClient.sendMessage(GeneralChannelId, say)
              passMessageToIrcAndIndex(None, say, true)
            case _ =>
              // Do nothing for now
          }
        } else {
          logger.debug(s"MESSAGE FROM SLACK: $msg")
          if (msg.channel == GeneralChannelId) {
            Users.findUserById(msg.user).map { user =>
              passMessageToIrcAndIndex(Some(user.name), msg.text, true)
            }
          }
        }
      case _: UserTyping => ()
      case e: MessageWithSubtype if e.messageSubType.isInstanceOf[FileShareMessage] && e.channel == GeneralChannelId =>
        handleFileUpload(e.messageSubType.asInstanceOf[FileShareMessage])
      case e =>
        logger.debug(s"Non-message: $e")
    }
  }

  private def handleFileUpload(msg: FileShareMessage) = {
    Users.findUserById(msg.file.user).foreach { user =>
      val title = msg.file.title
      val commentSuffix = msg.file.initial_comment match {
        case Some(comment) => s"[${comment.comment}]"
        case None => ""
      }

      SlackHTTP.makeFilePublic(msg.file.id).foreach { fileUrl =>
        /*
        passMessageToIrcAndIndex(
          None,
          s"OHOI! ${user.name} lisäsi kuvan $fileUrl $title $commentSuffix".trim,
          false
        )
        */
        sayInGeneralChannel(s"OHOI! ${user.name} lisäsi kuvan $fileUrl $title $commentSuffix".trim)
      }
    }
  }

  private def passMessageToIrcAndIndex(nickname: Option[String], text: String, replaceUserIds: Boolean) = {

  }

  def sayInGeneralChannel(text: String): Future[Long] = rtmClient.sendMessage(GeneralChannelId, text)

  object Users {
    private var t = DateTime.now.minusHours(1)
    private def isTimeToUpdateUsers = t.plusMinutes(1).isBeforeNow
    @volatile
    private var cachedUsers: Map[String, User] = Map.empty
    private def updateUsers(): Future[Map[String, User]] = {
      val f = apiClient.listUsers().map { us =>
        t = DateTime.now()
        cachedUsers = us.groupBy(_.id).map {
          case (id, values) => id -> values.head
        }
        cachedUsers
      }

      f.failed.foreach { err =>
        logger.error("Error updating users", err)
      }

      f
    }

    def filterUsers(rule: User => Boolean): Future[List[User]] = {
      if (cachedUsers.nonEmpty) {
        if (isTimeToUpdateUsers) {
          updateUsers()
        }
        Future.successful(cachedUsers.values.filter(rule).toList)
      } else {
        updateUsers().map(_.values.filter(rule).toList)
      }
    }

    def findUserById(id: String): Future[User] = {
      cachedUsers.get(id) match {
        case Some(user) =>
          if (isTimeToUpdateUsers) {
            updateUsers()
          }
          Future.successful(user)
        case None =>
          updateUsers().map(_.apply(id))
      }
    }
  }

  def main(args: Array[String]): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration._

    val users = Await.result(
      Users.filterUsers(_ => true),
      10.seconds
    )

    println(users.size)
    users.foreach(println)

    system.terminate()
  }

}

object SlackHTTP extends LoggerSupport {
  import com.softwaremill.sttp._

  implicit val backend = AsyncHttpClientFutureBackend()
  val userToken = Config.slackUserToken

  // https://api.slack.com/methods/files.sharedPublicURL
  def makeFilePublic(fileId: String)(implicit ec: ExecutionContext): Future[String] = {
    val async = sttp.get(uri"https://slack.com/api/files.sharedPublicURL?token=$userToken&file=$fileId").send.map { res =>
      import io.circe.parser._

      parse(res.unsafeBody).fold(
        err => throw new Exception(s"No json: $err"),
        js => js.hcursor.downField("file").downField("permalink_public").as[String].getOrElse {
          throw new Exception(s"Unexpected json: $js")
        }
      )
    }

    async.failed.foreach { err =>
      logger.error(s"Error in makeFilePublic($fileId): $err")
    }

    async
  }

  def main(args: Array[String]): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import scala.concurrent.ExecutionContext.Implicits.global

    val res = Await.result(
      makeFilePublic("FAVTYLD6K"),
      10.seconds
    )

    println(res)
  }

}
