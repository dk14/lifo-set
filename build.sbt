import CoverallsPlugin.CoverallsKeys._

name := "lifo"

version := "1.0"

scalaVersion := "2.11.5"

resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"

parallelExecution in Test := false //just to make logs nicer

libraryDependencies +="com.typesafe.akka" %% "akka-actor" % "2.4-SNAPSHOT"

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test"

libraryDependencies += "com.google.guava" % "guava" % "18.0"

libraryDependencies += "com.google.code.findbugs" % "jsr305" % "2.0.0" //to avoid https://issues.scala-lang.org/browse/SI-7751

coverallsToken := "UZOuZGCrfGFUX9OehFeOMbPptgSLJeqCP"