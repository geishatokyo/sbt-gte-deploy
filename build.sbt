sbtPlugin := true

publishMavenStyle := true

name := "sbt-gte-deploy"

organization := "com.geishatokyo"

description := "GeishaTokyo's deploy plugin.Deploy to AWS beanstalk with Docker"

version := "0.1.2-SNAPSHOT"

resolvers += Resolver.sbtPluginRepo("releases")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.0-RC1" % "provided")

libraryDependencies ++= Seq(
  "com.amazonaws" % "aws-java-sdk" % "1.11.2",
  "com.typesafe" % "config" % "1.3.0"
)

