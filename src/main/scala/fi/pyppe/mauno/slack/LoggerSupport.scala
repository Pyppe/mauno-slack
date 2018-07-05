package fi.pyppe.mauno.slack

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait LoggerSupport {
  protected val logger: Logger =
    Logger(LoggerFactory.getLogger(getClass.getName.replaceAll("\\$$", "")))
}
