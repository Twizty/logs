name := "logs"

version := "0.1"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "com.chuusai" %% "shapeless" % "2.3.3",
  "org.scalikejdbc"     %% "scalikejdbc-async" % "0.8.+",
  "com.github.mauricio" %% "postgresql-async"  % "0.2.+",
  "org.slf4j"           %  "slf4j-simple"      % "1.7.+",
  "org.typelevel" %% "cats-core" % "1.0.0-RC1",
  "org.typelevel" %% "cats-free" % "1.0.0-RC1",
//  "org.typelevel" %% "cats-effect" % "0.8",
  "com.typesafe.akka" %% "akka-http" % "10.0.10",
  "com.typesafe.akka" %% "akka-actor" % "2.5.6",
  "com.typesafe.akka" %% "akka-stream" % "2.5.6",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.0-RC2",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.6" % Test
)