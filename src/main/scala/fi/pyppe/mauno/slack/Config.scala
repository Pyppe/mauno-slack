package fi.pyppe.mauno.slack

import com.typesafe.config.ConfigFactory

object Config {

  private val conf = ConfigFactory.load("mauno.conf")

  def imakesHost: String = conf.getString("imakes.host")
  def slackBotToken = conf.getString("slack.bot.token")
  def slackUserToken = conf.getString("slack.user.token")

}
