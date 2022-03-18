// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import viper.gobra.frontend.{Config, PackageInfo, Source}
import viper.gobra.backend.ViperBackends
import viper.gobra.reporting.{FileWriterReporter, VerifierResult}
import org.eclipse.lsp4j.Range

import java.nio.file.{Path, Paths}
import ch.qos.logback.classic.Level
import org.bitbucket.inkytonik.kiama.util.Source
import viper.gobra.frontend.Source.FromFileSource
import viper.gobra.util.GobraExecutionContext
import viper.server.core.ViperCoreServer
import viper.silver.{ast => vpr}

object Helper {

  val defaultVerificationFraction = 0.75

  private def getPackageInfoInputMap(fileData: Vector[FileData]): Map[PackageInfo, Vector[Source]] = {
    // sort data (again) if it isn't already
    val sortedFileData = fileData.sortBy(_.fileUri)
    val sources = sortedFileData.map(_.filePath).map(path => FromFileSource(Paths.get(path)))
    sources.groupBy(Source.getPackageInfo(_, Path.of("")))
  }

  def verificationConfigFromTask(server: ViperCoreServer, config: VerifierConfig, startTime: Long, verify: Boolean, completedProgress: Int = 0, ast: Option[vpr.Program] = None)(executor: GobraExecutionContext): Config = {
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

        val backend = backendId match {
          case "SILICON" if serverMode => ViperBackends.ViperServerWithSilicon(Some(server))
          case "SILICON" => ViperBackends.SiliconBackend
          case "CARBON" if serverMode => ViperBackends.ViperServerWithCarbon(Some(server))
          case "CARBON" => ViperBackends.CarbonBackend
          case _ => ViperBackends.SiliconBackend
        }

        // ensure consistent ordering such that e.g. caching works as expected:
        val sortedFileData = fileData.sortBy(_.fileUri).toVector
        val reporter = GobraIdeReporter(
          startTime = startTime,
          verifierConfig = config,
          fileData = sortedFileData,
          backend = backend,
          verificationFraction = defaultVerificationFraction,
          progress = completedProgress,
          ast = ast,
          unparse = unparse,
          eraseGhost = eraseGhost,
          goify = goify,
          debug = debug,
          printInternal = printInternal,
          printVpr = printViper,
          logger = server.logger
        )(executor)

        Config(
          packageInfoInputMap = getPackageInfoInputMap(sortedFileData),
          moduleName = moduleName,
          includeDirs = includeDirs.map(Paths.get(_)).toVector,
          reporter = reporter,
          backend = backend,
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
      packageInfoInputMap = getPackageInfoInputMap(Vector(fileData)),
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
      packageInfoInputMap = getPackageInfoInputMap(fileData),
      shouldVerify = false,
      shouldViperEncode = viperPreview,
      reporter = reporter
    )
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