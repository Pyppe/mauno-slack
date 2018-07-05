name := "mauno-slack"

version := "1.0-SNAPSHOT"

scalaVersion := "2.12.6"

homepage := Some(url("https://github.com/Pyppe/mauno-slack"))

val appMainClass = "fi.pyppe.mauno.slack.App"
mainClass in (Compile, run) := Some(appMainClass)
mainClass in assembly := Some(appMainClass)
assemblyJarName in assembly := "mauno-slack.jar"

libraryDependencies ++= Seq(
  // Logging
  "ch.qos.logback"             %  "logback-classic" % "1.2.3",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",

  // HTTP
  "com.softwaremill.sttp"      %% "core" % "1.2.1",
  "com.softwaremill.sttp"      %% "async-http-client-backend-future" % "1.2.1",
  "com.softwaremill.sttp"      %% "circe" % "1.2.1",

  // Akka logging
  "com.typesafe.akka"          %% "akka-slf4j" % "2.4.20",

  // Slack
  "com.github.gilbertw1"       %% "slack-scala-client" % "0.2.3",

  // XML (for RSS parsing)
  "org.scala-lang.modules"     %% "scala-xml" % "1.1.0"

)

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
  case x =>
    (assemblyMergeStrategy in assembly).value(x) // Use the old strategy as default
}
