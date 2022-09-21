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
    version := "0.0.1",
    organization := "viper",
    homepage := Some(url("https://github.com/viperproject/gobra-ide")),
    licenses := Seq("MPL-2.0 License" -> url("https://opensource.org/licenses/MPL-2.0")),

    libraryDependencies += "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.8.1", // Java implementation of language server protocol
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
  )
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](
      "projectName" -> name.value,
      "projectVersion" -> version.value,
      scalaVersion,
      sbtVersion,
      BuildInfoKey.action("git") {
        val revision = Try(Process("git rev-parse HEAD").!!.trim).getOrElse("<revision>")
        val branch = Try(Process("git rev-parse --abbrev-ref HEAD").!!.trim).getOrElse("<branch>")
        Map("revision" -> revision, "branch" -> branch)
      }
    ),
    buildInfoPackage := "viper.gobraserver"
  )
