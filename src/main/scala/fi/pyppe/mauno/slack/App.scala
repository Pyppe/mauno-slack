package fi.pyppe.mauno.slack

object App {

  def main(args: Array[String]): Unit = {
    ImakesRssChecker.Scheduler.start()
    Slack.registerSlackGateway()
  }

}
