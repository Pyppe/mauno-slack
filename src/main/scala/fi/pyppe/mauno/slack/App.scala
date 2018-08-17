package fi.pyppe.mauno.slack

object App extends LoggerSupport {

  def main(args: Array[String]): Unit = {
    logger.info("Starting app...")
    Slack.registerSlackGateway()
    ImakesRssChecker.Scheduler.start()
  }

}
