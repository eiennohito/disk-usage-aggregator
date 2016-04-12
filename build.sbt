name := """udp-aggregator"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala).dependsOn(agent)

lazy val agent = (project in file("agent"))

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws
)

libraryDependencies += "com.typesafe.scala-logging" % "scala-logging-slf4j_2.11" % "2.1.2"

libraryDependencies += "com.novus" %% "salat" % "1.9.9"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.0-M11" % "test"

libraryDependencies += "org.gridkit.lab" % "nanocloud" % "0.8.9"

libraryDependencies += "org.hdrhistogram" % "HdrHistogram" % "2.1.8"

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator
