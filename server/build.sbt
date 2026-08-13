// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import scala.sys.process.Process
import scala.util.Try

// Import general settings from Gobra and ViperServer
lazy val gobra = project in file("gobra")

lazy val gobraServer = (project in file("."))
  .dependsOn(gobra % "compile->compile;test->test")
  .settings(
    name := "gobra-ide",
    description := "Server implementation for Gobra IDE",
    version := "1.0.0",
    organization := "viper",
    homepage := Some(url("https://github.com/viperproject/gobra-ide")),
    licenses := Seq("MPL-2.0 License" -> url("https://opensource.org/licenses/MPL-2.0")),

    // Java implementation of language server protocol.
    // note that the classpath contains only a single lsp4j version: on a version conflict, sbt
    // evicts all but the highest version. Since ViperServer (a transitive dependency via Gobra)
    // declares lsp4j as well, keep this version in sync with ViperServer's — declaring a lower
    // version here would silently be evicted in favor of ViperServer's:
    libraryDependencies += "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.20.1",
	  libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.6.10",

	  scalacOptions ++= Seq(
      "-Ypatmat-exhaust-depth", "40"
    ),

	  // Run settings
    run / javaOptions += "-Xss128m",

	  fork := true,

	  // Test settings
	  Test / javaOptions ++= (run / javaOptions).value,

    // Assembly settings
    assembly / assemblyJarName := "server.jar",
    assembly / mainClass := Some("viper.gobraserver.Server"),
	  assembly / javaOptions += "-Xss128m",
    assembly / assemblyMergeStrategy := {
      case LogbackConfigurationFilePattern() => MergeStrategy.first
      case x =>
        val fallbackStrategy = (assembly / assemblyMergeStrategy).value
        fallbackStrategy(x)
    }
  )
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](
      "projectName" -> name.value,
      "projectVersion" -> version.value,
      scalaVersion,
      sbtVersion,
      BuildInfoKey.action("gitRevision")(Try(Process("git rev-parse HEAD", baseDirectory.value).!!.trim).getOrElse("<revision>")),
      BuildInfoKey.action("gitBranch")(Try(Process("git rev-parse --abbrev-ref HEAD", baseDirectory.value).!!.trim).getOrElse("<branch>"))
    ),
    buildInfoPackage := "viper.gobraserver"
  )

lazy val LogbackConfigurationFilePattern = """logback.*?\.xml""".r
