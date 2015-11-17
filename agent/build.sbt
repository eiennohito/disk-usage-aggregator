name := "collector-agent"

version := "0.1-SNAPSHOT"

crossPaths := false

autoScalaLibrary := false

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

mainClass := Some("org.eiennohito.CollectorAgent")
