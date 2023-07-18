// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import viper.gobra.frontend.{BaseConfig, Config, FileModeConfig, PackageInfo, Source}
import viper.gobra.backend.ViperBackends
import viper.gobra.reporting
import viper.gobra.reporting.{FileWriterReporter, VerifierResult}
import org.eclipse.lsp4j.{Position, Range}

import java.nio.file.{Path, Paths}
import ch.qos.logback.classic.Level
import org.bitbucket.inkytonik.kiama.util.Source
import viper.gobra.frontend.Source.FromFileSource
import viper.gobra.reporting.Source.Verifier
import viper.gobra.util.GobraExecutionContext
import viper.server.core.ViperCoreServer
import viper.silver.ast.{HasLineColumn, SourcePosition}
import viper.silver.{ast => vpr}

import java.net.URI

object Helper {

  val defaultVerificationFraction = 0.75

  def uri2Path(uri: String): Path = {
    Paths.get(new URI(uri))
  }

  private def getPackageInfoInputMap(fileData: Vector[FileData]): Map[PackageInfo, Vector[Source]] = {
    // sort data (again) if it isn't already
    val sortedFileData = fileData.sortBy(_.fileUri)
    val sources = sortedFileData.map(fileDatum => FromFileSource(uri2Path(fileDatum.fileUri)))
    sources.groupBy(Source.getPackageInfo(_, Path.of("")))
  }

  def getFileModeConfig(server: ViperCoreServer, config: VerifierConfig, startTime: Long, stopAfterEncoding: Boolean, completedProgress: Int = 0, ast: Option[vpr.Program] = None)(executor: GobraExecutionContext): FileModeConfig = {
    config match {
      case VerifierConfig(
        fileData,
        isolate,
        GobraSettings(backendId, serverMode, debug, eraseGhost, goify, unparse, printInternal, printViper, parseOnly, logLevel, moduleName, includeDirs),
        z3Exe,
        boogieExe
      ) =>
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
          logger = server.globalLogger
        )(executor)

        val convertedIsolationData = convertIsolationData(isolate)

        val inputFiles = sortedFileData.map(fileDatum => uri2Path(fileDatum.fileUri))
        val baseConfig = BaseConfig(
          moduleName = moduleName,
          includeDirs = includeDirs.map(Paths.get(_)).toVector,
          reporter = reporter,
          backend = backend,
          isolate = convertedIsolationData,
          z3Exe = Some(z3Exe),
          boogieExe = Some(boogieExe),
          logLevel = Level.toLevel(logLevel),
          shouldParseOnly = parseOnly,
          stopAfterEncoding = stopAfterEncoding,
          parallelizeBranches = true,
          cacheParserAndTypeChecker = true,
        )
        FileModeConfig(inputFiles = inputFiles, baseConfig = baseConfig)
    }
  }

  def convertIsolationData(data: Array[IsolationData]): List[(Path, List[Int])] =
    data.map(isolationDatum => (uri2Path(isolationDatum.fileUri), isolationDatum.lineNrs.toList)).toList

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

  /**
    * Returns the information about verified members based on `isolate` data.
    * Returns an empty array if the entire program has been verified and a non-empty array otherwise.
    */
  def getVerifiedMemberInfo(isolate: Array[IsolationData], ast: Option[vpr.Program], success: Boolean): Array[MemberInformation] = isolate match {
    case Array() => Array.empty
    case _ =>
      /** checks whether `fileUri` and `lineNr` is within `pos` */
      def contains(pos: SourcePosition, path: Path, lineNr: Int): Boolean = {
        pos.file == path && (pos.end match {
          case Some(lc: HasLineColumn) => pos.start.line <= lineNr && lineNr <= lc.line
          case _ => lineNr == pos.start.line // exact match needed because no `end` position is provided
        })
      }

      def memberInfoForInfo(info: Verifier.Info): MemberInformation = {
        val pos = info.origin.pos
        // not that line and column has to be decremented by one as `Position` and `Range` are zero-based
        val start = new Position(pos.start.line - 1, pos.start.column - 1)
        val end = pos.end.map(endPos => new Position(endPos.line - 1, endPos.column - 1)).getOrElse(start)
        val range = new Range(start, end)
        MemberInformation(isUnknown = false, fileUri = pos.file.toUri.toString, success = success, range = range)
      }

      def unknownMemberInfo(path: Path): MemberInformation =
        MemberInformation(isUnknown = true, fileUri = path.toUri.toString, success = success, range = new Range())

      val convertedIsolationData = convertIsolationData(isolate)
      /** contains each isolated line together with the corresponding path */
      val isolatedLines = convertedIsolationData.flatMap {
        case (path, lineNrs) => lineNrs.map(lineNr => (path, lineNr))
      }
      isolatedLines.map {
        case (path, lineNr) =>
          ast.flatMap(a => a.collectFirst {
            case MemberSource(info) if contains(info.origin.pos, path, lineNr) => memberInfoForInfo(info)
          }).getOrElse(unknownMemberInfo(path))
      }.toArray
  }

  def getOverallVerificationResult(fileUris: Array[String], isolate: Array[IsolationData], ast: Option[vpr.Program], result: VerifierResult, elapsedTime: Long): OverallVerificationResult = {
    result match {
      case VerifierResult.Success =>
        OverallVerificationResult(
          fileUris = fileUris,
          success = true,
          message = s"Verification succeeded in ${elapsedTime/1000}.${(elapsedTime%1000)/10}s",
          getVerifiedMemberInfo(isolate, ast, success = true)
        )
      case VerifierResult.Failure(errors) =>
        OverallVerificationResult(
          fileUris = fileUris,
          success = false,
          message = s"Verification failed in ${elapsedTime / 1000}.${(elapsedTime%1000)/10}s with: ${errors.head.id}",
          getVerifiedMemberInfo(isolate, ast, success = false)
        )
    }
  }

  def getOverallVerificationResultFromException(fileUris: Array[String], isolate: Array[IsolationData], ast: Option[vpr.Program], e: Throwable): OverallVerificationResult = {
    OverallVerificationResult(
      fileUris = fileUris,
      success = false,
      message = e.getMessage,
      getVerifiedMemberInfo(isolate, ast, success = false)
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

/** restricts `Source` to only match on `Member` AST nodes (instead of arbitrary AST nodes) */
object MemberSource {
  def unapply(node: vpr.Member): Option[Verifier.Info] = {
    reporting.Source.unapply(node)
  }
}
