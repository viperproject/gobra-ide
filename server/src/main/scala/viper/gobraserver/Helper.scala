// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import viper.gobra.frontend.{Config, Source}
import viper.gobra.backend.{ViperBackends, ViperVerifierConfig}
import viper.gobra.reporting.{FileWriterReporter, VerifierResult}
import org.eclipse.lsp4j.Range

import java.nio.file.{Files, Paths}
import ch.qos.logback.classic.Level
import org.bitbucket.inkytonik.kiama.util.Source
import viper.gobra.util.GobraExecutionContext
import viper.gobraserver.backend.{ViperServerBackend, ViperServerConfig}
import viper.silver.logger.ViperLogger

object Helper {

  val defaultVerificationFraction = 0.75

  def verificationConfigFromTask(config: VerifierConfig, startTime: Long, verify: Boolean, progress: Int = 0, logger: ViperLogger)(executor: GobraExecutionContext): Config = {
    config match {
      case VerifierConfig(
        fileData,
        GobraSettings(backendId, serverMode, debug, eraseGhost, goify, unparse, printInternal, printViper, parseOnly, logLevel, moduleName, includeDirs),
        z3Exe,
        boogieExe
      ) =>

        val shouldParse = true
        val shouldTypeCheck = !parseOnly
        val shouldDesugar = shouldTypeCheck
        val shouldViperEncode = shouldDesugar
        val shouldVerify = shouldViperEncode && verify

        val backend =
          if (serverMode) {
            ViperServerBackend
          } else {
            backendId match {
              case "SILICON" => ViperBackends.SiliconBackend
              case "CARBON" => ViperBackends.CarbonBackend
              case _ => ViperBackends.SiliconBackend
            }
          }

        // ensure consistent ordering such that e.g. caching works as expected:
        val sortedFileData = fileData.sortBy(_.fileUri).toVector
        val fileUris = sortedFileData.map(_.fileUri)
        val filePaths = sortedFileData.map(_.filePath)
        val reporter = GobraIdeReporter(
          startTime = startTime,
          verifierConfig = config,
          fileData = sortedFileData,
          backend = backend,
          verificationFraction = defaultVerificationFraction,
          progress = progress,
          unparse = unparse,
          eraseGhost = eraseGhost,
          goify = goify,
          debug = debug,
          printInternal = printInternal,
          printVpr = printViper,
          logger = logger
        )(executor)

        val verifierConfig =
          if (serverMode) {
            var options: Vector[String] = Vector.empty

            if (z3Exe != null && Files.exists(Paths.get(z3Exe)))
              options ++= Vector("--z3Exe", z3Exe)

            backendId match {
              case "SILICON" =>
                //var options: List[String] = List()
                options ++= Vector("--logLevel", "ERROR")
                options ++= Vector("--disableCatchingExceptions")
                options ++= Vector("--enableMoreCompleteExhale")

                ViperServerConfig.ConfigWithSilicon(options.toList)

              case "CARBON" =>
                //var options: List[String] = List()
                if (boogieExe != null && Files.exists(Paths.get(boogieExe)))
                  options ++= Vector("--boogieExe", boogieExe)

                ViperServerConfig.ConfigWithCarbon(options.toList)

              case _ =>
                println("unknown backend option received - falling back to Silicon")
                ViperServerConfig.EmptyConfigWithSilicon
            }
          } else {
            // this won't be used as ViperServer will not be involved.
            // nevertheless, we need to provide a config
            ViperVerifierConfig.EmptyConfig
          }

        Config(
          inputs = filePaths.map(getSourceFromPath),
          moduleName = moduleName,
          includeDirs = includeDirs.map(Paths.get(_)).toVector,
          reporter = reporter,
          backend = backend,
          backendConfig = verifierConfig,
          z3Exe = Some(z3Exe),
          boogieExe = Some(boogieExe),
          logLevel = Level.toLevel(logLevel),
          shouldParse = shouldParse,
          shouldTypeCheck = shouldTypeCheck,
          shouldDesugar = shouldDesugar,
          shouldViperEncode = shouldViperEncode,
          shouldVerify = shouldVerify,
          cacheParser = true
        )
    }
  }

  def goifyConfigFromTask(fileData: FileData): Config = {
    val reporter = FileWriterReporter(goify = true)

    Config(
      inputs = Vector(getSourceFromPath(fileData.filePath)),
      shouldDesugar = false,
      shouldViperEncode = false,
      shouldVerify = false,
      reporter = reporter
    )
  }

  def previewConfigFromTask(fileData: Vector[FileData], internalPreview: Boolean, viperPreview: Boolean, selections: List[Range]): Config = {
    val reporter = PreviewReporter(
      internalPreview = internalPreview,
      viperPreview = viperPreview,
      selections = selections
    )

    Config(
      inputs = fileData.map(f => getSourceFromPath(f.filePath)),
      shouldVerify = false,
      shouldViperEncode = viperPreview,
      reporter = reporter
    )
  }

  private def getSourceFromPath(path: String): Source = {
    Source.getSource(Paths.get(path))
  }

  def getOverallVerificationResult(fileUris: Vector[String], result: VerifierResult, elapsedTime: Long): OverallVerificationResult = {
    result match {
      case VerifierResult.Success =>
        OverallVerificationResult(
          fileUris = fileUris.toArray,
          success = true,
          message = "Verification succeeded in " + (elapsedTime/1000) + "." + (elapsedTime%1000)/10 + "s"
        )
      case VerifierResult.Failure(errors) =>
        OverallVerificationResult(
          fileUris = fileUris.toArray,
          success = false,
          message = "Verification failed in " + (elapsedTime / 1000) + "." + (elapsedTime%1000)/10 + "s with: " + errors.head.id
        )
    }
  }

  def getOverallVerificationResultFromException(fileUris: Vector[String], e: Throwable): OverallVerificationResult = {
    OverallVerificationResult(
      fileUris = fileUris.toArray,
      success = false,
      message = e.getMessage
    )
  }


  def startLine(range: Range): Int = range.getStart.getLine
  def startChar(range: Range): Int = range.getStart.getCharacter
  def endLine(range: Range): Int = range.getEnd.getLine
  def endChar(range: Range): Int = range.getEnd.getCharacter

  def gobraFileExtension(uri: String): String = {
    val dropSuffix = if (uri.endsWith(".go")) uri.dropRight(3) else uri
    if (dropSuffix.endsWith(".gobra")) dropSuffix else dropSuffix + ".gobra"
  }

  def indent(code: String, block: String): String = {
    var tmp = block
    while (!code.contains(tmp)) tmp = " " + tmp.replaceAll("\n", "\n ")
    tmp
  }

  def optToSeq[A](singleton: Option[A]): Seq[A] = singleton match {
    case Some(s) => Seq(s)
    case None => Seq()
  }
}