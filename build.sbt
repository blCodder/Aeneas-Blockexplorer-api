organization in ThisBuild := "AeneasPlatform"

name := "Aeneas"

version := "0.0.1"

scalaVersion := "2.12.4"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

resolvers += Resolver.bintrayRepo("parabellum1905y","maven")

val typesafeDependencies = Seq (
  "com.typesafe.akka" %% "akka-http" % "10.0.11",
  "com.typesafe.akka" %% "akka-stream" % "2.5.5",
  "com.typesafe.akka" %% "akka-actor"  % "2.5.5",
  "com.typesafe" % "config" % "1.3.1"
)

val testDependencies = Seq(
  "org.scalactic" %% "scalactic" % "3.0.3",
  "org.scalatest" %% "scalatest" % "3.0.3" % "test",
  "com.dimafeng" %% "testcontainers-scala" % "0.14.0" % "test"
)

val loggingDependencies = Seq(
  //"tv.cntt" %% "slf4s-api" % "1.7.25",
  /*Fork of https://github.com/mattroberts297/slf4s to add support for Scala 2.12.*/
  //"com.typesafe.scala-logging" %% "scala-logging" % "3.+",
  "org.slf4j" % "slf4j-api" % "1.8.0-beta1",
  "ch.qos.logback" % "logback-core" % "1.3.0-alpha4",
  "ch.qos.logback" % "logback-classic" % "1.3.0-alpha4"
)

val scorexDependencies = Seq(
  "org.scorexfoundation" % "scorex-core_2.12" % "master-05508f49",
  "scorex-testkit" % "scorex-testkit_2.12" % "master-05508f49",
  "org.scorexfoundation" %% "scrypto" % "2.+",
  "org.scorexfoundation" %% "iodb" % "0.3.2"
)

val circeVersion = "0.8.0"

libraryDependencies in ThisBuild ++= Seq(
  "com.iheart" %% "ficus" % "1.4.2",
  "org.scorexfoundation" %% "scrypto" % "2.+",
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "org.bitlet" % "weupnp" % "0.1.+",
  "commons-net" % "commons-net" % "3.+"

) ++ testDependencies ++ loggingDependencies ++ typesafeDependencies ++ scorexDependencies

mainClass in assembly := Some("SimpleBlockChain")

test in assembly := {}//TODO
assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case "reference.conf" => MergeStrategy.concat
  case x => MergeStrategy.first
}
