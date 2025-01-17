// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import viper.gobra.frontend.{BaseConfig, Config, FileModeConfig, PackageInfo, Source}
import viper.gobra.backend.ViperBackends
import viper.gobra.reporting
import viper.gobra.reporting.{FileWriterReporter, GobraReporter, VerifierError, VerifierResult}
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

  private def getPackageInfoInputMap(fileData: Vector[FileData]): Either[Vector[VerifierError], Map[PackageInfo, Vector[Source]]] = {
    // sort data (again) if it isn't already
    val sortedFileData = fileData.sortBy(_.fileUri)
    val sources = sortedFileData.map(fileDatum => FromFileSource(uri2Path(fileDatum.fileUri)))
    val eitherSourceAndPkgInfos = sources.map(source =>
      for {
        pkgInfo <- Source.getPackageInfo(source, Path.of(""))
      } yield (source, pkgInfo))
    val (errors, sourceAndPkgInfos) = eitherSourceAndPkgInfos.partitionMap(identity)
    if (errors.nonEmpty) {
      Left(errors.flatten)
    } else {
      val pkgGroups = sourceAndPkgInfos.groupBy(_._2)
      // remove package infos from map's values:
      Right(pkgGroups.map { case (pkgInfo, sourcesAndInfos) => (pkgInfo, sourcesAndInfos.map(_._1)) })
    }
  }

  def getReporter(config: VerifierConfig, server: ViperCoreServer, startTime: Long, stopAfterEncoding: Boolean, completedProgress: Int = 0, ast: Option[vpr.Program] = None)(executor: GobraExecutionContext): GobraReporter with VerificationFinishNotifier = {
    // ensure consistent ordering such that e.g. caching works as expected:
    val sortedFileData = config.fileData.sortBy(_.fileUri).toVector
    GobraIdeReporter(
      startTime = startTime,
      verifierConfig = config,
      fileData = sortedFileData,
      verificationFraction = defaultVerificationFraction,
      progress = completedProgress,
      ast = ast,
      unparse = config.gobraSettings.unparse,
      eraseGhost = config.gobraSettings.eraseGhost,
      goify = config.gobraSettings.goify,
      debug = config.gobraSettings.debug,
      printInternal = config.gobraSettings.printInternal,
      printVpr = config.gobraSettings.printViper,
      submitAstJob = stopAfterEncoding,
      cacheDiagnostics = config.gobraSettings.serverMode,
      logger = server.globalLogger
    )(executor)
  }

  def getFileModeConfig(config: VerifierConfig, server: ViperCoreServer, reporter: GobraReporter, stopAfterEncoding: Boolean): FileModeConfig = {
    val backend = config.gobraSettings.backend match {
      case "SILICON" if config.gobraSettings.serverMode => ViperBackends.ViperServerWithSilicon(Some(server))
      case "SILICON" => ViperBackends.SiliconBackend
      case "CARBON" if config.gobraSettings.serverMode => ViperBackends.ViperServerWithCarbon(Some(server))
      case "CARBON" => ViperBackends.CarbonBackend
      case _ => ViperBackends.SiliconBackend
    }

    val convertedIsolationData = convertIsolationData(config.isolate)

    // ensure consistent ordering such that e.g. caching works as expected:
    val sortedFileData = config.fileData.sortBy(_.fileUri).toVector
    val inputFiles = sortedFileData.map(fileDatum => uri2Path(fileDatum.fileUri))
    val baseConfig = BaseConfig(
      moduleName = config.gobraSettings.moduleName,
      includeDirs = config.gobraSettings.includeDirs.map(Paths.get(_)).toVector,
      reporter = reporter,
      backend = backend,
      isolate = convertedIsolationData,
      z3Exe = Some(config.z3Executable),
      boogieExe = Some(config.boogieExecutable),
      logLevel = Level.toLevel(config.gobraSettings.logLevel),
      shouldParseOnly = config.gobraSettings.parseOnly,
      stopAfterEncoding = stopAfterEncoding,
      parallelizeBranches = true,
      cacheParserAndTypeChecker = true,
    )
    FileModeConfig(inputFiles = inputFiles, baseConfig = baseConfig)
  }

  def convertIsolationData(data: Array[IsolationData]): List[(Path, List[Int])] =
    data.map(isolationDatum => (uri2Path(isolationDatum.fileUri), isolationDatum.lineNrs.toList)).toList

  def goifyConfigFromTask(fileData: FileData): Either[Vector[VerifierError], Config] = {
    val reporter = FileWriterReporter(goify = true)

    for {
      pkgInfo <- getPackageInfoInputMap(Vector(fileData))
      config = Config(
        packageInfoInputMap = pkgInfo,
        shouldDesugar = false,
        shouldViperEncode = false,
        shouldVerify = false,
        reporter = reporter
      )
    } yield config
  }

  def previewConfigFromTask(fileData: Vector[FileData], internalPreview: Boolean, viperPreview: Boolean, selections: List[Range]): Either[Vector[VerifierError], Config] = {
    val reporter = PreviewReporter(
      internalPreview = internalPreview,
      viperPreview = viperPreview,
      selections = selections
    )

    for {
      pkgInfo <- getPackageInfoInputMap(fileData)
      config = Config(
        packageInfoInputMap = pkgInfo,
        shouldVerify = false,
        shouldViperEncode = viperPreview,
        reporter = reporter
      )
    } yield config
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
