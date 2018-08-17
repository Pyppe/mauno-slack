package fi.pyppe.mauno.slack

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait LoggerSupport { self =>
  protected lazy val logger: Logger =
    Logger(LoggerFactory.getLogger(self.getClass.getName.replaceAll("\\$$", "")))
}
