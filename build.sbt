val scala3Version = "3.3.7"

val http4sVersion      = "0.23.34"
val http4sNettyVersion = "0.5.28"
val jsoniterVersion    = "2.38.9"
val munitVersion       = "1.2.4"
val munitCEVersion     = "2.0.0"
val logbackVersion     = "1.5.18"

lazy val root = project
  .in(file("."))
  .settings(
    name         := "rinha-2026-scala",
    version      := "0.1.0",
    scalaVersion := scala3Version,
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Wunused:imports,privates,locals",
      "-encoding",
      "utf8"
    ),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies ++= Seq(
      "org.http4s"                            %% "http4s-dsl"          % http4sVersion,
      "org.http4s"                            %% "http4s-netty-server" % http4sNettyVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion % "provided",
      "ch.qos.logback" % "logback-classic"   % logbackVersion % Runtime,
      "org.scalameta" %% "munit"             % munitVersion   % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCEVersion % Test
    ),
    assembly / mainClass       := Some("rinha.Main"),
    assembly / assemblyJarName := "app.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
      case PathList("META-INF", "native", xs @ _*)   => MergeStrategy.first
      case PathList("META-INF", "versions", xs @ _*) => MergeStrategy.first
      case PathList("META-INF", "MANIFEST.MF")       => MergeStrategy.discard
      case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
      case "module-info.class"                       => MergeStrategy.discard
      case x                                         => MergeStrategy.first
    }
  )
