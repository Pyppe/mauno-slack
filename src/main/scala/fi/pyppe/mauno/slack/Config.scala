package fi.pyppe.mauno.slack

import com.typesafe.config.ConfigFactory

object Config {

  private val conf = ConfigFactory.load("mauno.conf")

  val botName = "maunomies"

  def elasticHost = conf.getString("elastic.host")
  def imakesHost = conf.getString("imakes.host")
  def slackBotToken = conf.getString("slack.bot.token")
  def slackUserToken = conf.getString("slack.user.token")

}
