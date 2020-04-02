import scala.sys.process.Process
import scala.util.Try

// Compilation settings
ThisBuild / scalaVersion := "2.12.7"
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",                     // Warn when using deprecated language features
  "-unchecked",                       // Warn on generated code assumptions
  "-feature",                         // Warn on features that requires explicit import
  "-Ywarn-unused-import",             // Warn on unused imports
  "-Ypatmat-exhaust-depth", "40",     // Increase depth of pattern matching analysis
)

lazy val server = (project in file("gobra"))

lazy val gobraServer = (project in file("."))
  .dependsOn(server %"compile->compile;test->test")
  .settings(
    name := "gobra-ide",
    description := "Server implementation for Gobra IDE",
    version := "0.0.1",
    organization := "viper",
//    licenses := Seq("MPL-2.0 License" -> url("https://opensource.org/licenses/MPL-2.0")),

    libraryDependencies += "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.8.1", // Java implementation of language server protocol

	scalacOptions ++= Seq(
      "-Ypartial-unification",
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
