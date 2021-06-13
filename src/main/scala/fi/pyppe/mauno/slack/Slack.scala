package fi.pyppe.mauno.slack

import akka.actor.ActorSystem
import com.softwaremill.sttp.asynchttpclient.future.AsyncHttpClientFutureBackend
import fi.pyppe.mauno.slack.Utils.unescapeHtml
import io.circe.Json
import io.circe.syntax.StringOps
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}
import slack.SlackUtil
import slack.api.SlackApiClient
import slack.models.{FileShared, Hello, Message, User, UserTyping}
import slack.rtm.SlackRtmClient

object Slack extends LoggerSupport {

  def props(key: String) = Option(System.getProperty(key)).getOrElse {
    throw new Exception(s"System property <$key> not found")
  }
  val GeneralChannelId = "C0QAPKH36"
  val PyppeUserId = "U0XSM1QN6" // pyppe
  val ProxyUserId = "UAY6VSAE8" // Manolito

  private val SayCommand = """!say +(.+)""".r
  private val UserIdPattern = s"""<@(\\w+)>""".r
  private val ProxiedMessage = s"""[*]?<(\\S+)>[*]? *(.*)""".r

  implicit val system = ActorSystem("slack")
  implicit val ec = system.dispatcher

  val rtmClient = SlackRtmClient(Config.slackBotToken)
  val botId = rtmClient.state.self.id
  val apiClient = SlackApiClient(Config.slackBotToken)

  def registerSlackGateway() = {
    rtmClient.onEvent {
      case msg: Message =>
        msg.bot_id.foreach { botId =>
          logger.debug(s"BOT_ID: $botId")
        }
        if (SlackUtil.isDirectMsg(msg)) {
          msg.text match {
            case SayCommand(say) if say.trim.nonEmpty =>
              val text = unescapeHtml(say)
              sayInGeneralChannel(text)
              indexMessage(None, text, true)
            case _ =>
              // Do nothing for now
          }
        } else {
          logger.debug(s"MESSAGE FROM SLACK: $msg")
          if (msg.channel == GeneralChannelId) {
            handleTextualMessageToGeneral(msg.user, msg.text)
          }
        }
      case _: UserTyping => ()
      case _: Hello => ()
      /*
      case e: MessageWithSubtype if e.messageSubType.isInstanceOf[FileShareMessage] && e.channel == GeneralChannelId =>
        handleFileUpload(e.messageSubType.asInstanceOf[FileShareMessage])
      */
      case FileShared(id) =>
        handleFileUpload(id)
        /*
      case e: MessageWithSubtype if e.messageSubType.isInstanceOf[MeMessage] && e.channel == GeneralChannelId =>
        handleTextualMessageToGeneral(e.user, e.text)*/
      case e =>
        logger.debug(s"Got event: $e")
    }

    logger.info("Slack registered")
  }

  private def handleTextualMessageToGeneral(userId: String, originalText: String) = {
    Users.findUserById(userId).map { user =>
      val text = unescapeHtml(originalText)
      if (user.id == ProxyUserId) {
        text match {
          case ProxiedMessage(nick, text) =>
            indexMessage(Some(nick), text, false)
          case _ =>
            logger.warn(s"Unexpected proxy message: $originalText")
        }
      } else {
        indexMessage(Some(user.name), text, true)
      }
    }
  }

  private def handleFileUpload(fileId: String) = {
    for {
      fileInfo <- SlackHTTP.makeFilePublic(fileId)
      user <- Users.findUserById(fileInfo.userId)
    } yield {
      val title = unescapeHtml(fileInfo.title)
      val commentSuffix = fileInfo.comment match {
        case Some(comment) => s"[${unescapeHtml(comment)}]"
        case None => ""
      }

      val text = s"OHOI! ${user.name} lisäsi kuvan ${fileInfo.permalinkPublic} $title $commentSuffix".trim
      apiClient.postChatMessage(
        GeneralChannelId,
        text,
        unfurlLinks = Some(false),
        unfurlMedia = Some(false)
      )
      indexMessage(None, text, true)
    }
  }

  private def indexMessage(nickname: Option[String], text: String, replaceUserIds: Boolean): Future[Unit] = {

    def index(text: String) = {
      Elastic.save(
        IndexedMessage.create(
          nickname getOrElse Config.botName,
          text
        )
      )
    }

    if (replaceUserIds) {
      val userIds = UserIdPattern.findAllMatchIn(text).map(_.group(1)).toSet

      if (userIds.nonEmpty) {
        Future.traverse(userIds)(Users.findUserById).flatMap { users =>
          val usersById = users.groupBy(_.id).mapValues(_.head)
          index(
            UserIdPattern.replaceAllIn(text, m => {
              usersById.get(m.group(1)).map { user =>
                s"@${user.name}"
              } getOrElse m.group(0)
            })
          )
        }
      } else {
        index(text)
      }
    } else index(text)
  }

  def sayInGeneralChannel(text: String): Future[Long] =
    rtmClient.sendMessage(GeneralChannelId, text)

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

  case class FileInfo(id: String, title: String, comment: Option[String], permalinkPublic: String, userId: String)

  // https://api.slack.com/methods/files.sharedPublicURL
  def makeFilePublic(fileId: String)(implicit ec: ExecutionContext): Future[FileInfo] = {
    val async = sttp.get(uri"https://slack.com/api/files.sharedPublicURL?token=$userToken&file=$fileId").send.map { res =>
      import io.circe.parser._

      parse(res.unsafeBody).fold(
        err => throw new Exception(s"No json: $err"),
        js => parseFileResponse(js).right.get
      )
    }

    async.failed.foreach { err =>
      logger.error(s"Error in makeFilePublic($fileId): $err")
    }

    async
  }

  private def parseFileResponse(js: Json) = {
    val file = js.hcursor.downField("file")

    for {
      id <- file.downField("id").as[String]
      title <- file.downField("title").as[String]
      link <- file.downField("permalink_public").as[String]
      userId <- file.downField("user").as[String]
    } yield {
      FileInfo(
        id,
        title,
        Option(
          file.downField("initial_comment").downField("comment").as[String].getOrElse("")
        ).filter(_.nonEmpty),
        link,
        userId
      )
    }
  }

  // https://api.slack.com/methods/files.info
  def fileInfo(fileId: String)(implicit ec: ExecutionContext): Future[FileInfo] = {
    val async = sttp.get(uri"https://slack.com/api/files.info?token=$userToken&file=$fileId").send.map { res =>
      import io.circe.parser._

      parseFileResponse(parse(res.unsafeBody).right.get).right.get
    }

    async.failed.foreach { err =>
      logger.error(s"Error in fileInfo($fileId): $err")
    }

    async
  }

  def main(args: Array[String]): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import scala.concurrent.ExecutionContext.Implicits.global

    try {
      val res = Await.result(
        fileInfo("FAVTYLD6K"),
        10.seconds
      )

      println(res)
    } finally {
      backend.close()
    }

  }

}
