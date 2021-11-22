// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import org.apache.commons.io.FileUtils
import org.eclipse.lsp4j.{Diagnostic, DiagnosticSeverity, Position, Range}
import viper.gobra.backend.ViperBackend
import viper.gobra.reporting._
import viper.gobra.util.{GobraExecutionContext, OutputUtil, Violation}
import viper.gobraserver.backend.ViperServerBackend
import viper.silver.logger.ViperLogger
import viper.silver.reporter.StatisticsReport

import scala.collection.mutable

/**
  * There is a GobraIdeReporter per verification unit, i.e. a set of files that are verified together. This can be
  * the files belonging to the same package.
  */
case class GobraIdeReporter(name: String = "gobraide_reporter",
                            startTime: Long,
                            verifierConfig: VerifierConfig,
                            fileData: Vector[FileData],
                            backend: ViperBackend,
                            verificationFraction: Double,
                            var progress: Int,
                            unparse: Boolean = false,
                            eraseGhost: Boolean = false,
                            goify: Boolean = false,
                            debug: Boolean = false,
                            printInternal: Boolean = false,
                            printVpr: Boolean = false,
                            logger: ViperLogger)(executor: GobraExecutionContext) extends GobraReporter {

  private lazy val fileUris: Vector[String] = fileData.map(_.fileUri)

  /**
    * State and Helper functions used for tracking the progress of the Verification.
    */
  private def nonVerificationEntityProgress: Int = ((1 - verificationFraction) * 25).round.toInt
  private def preprocessEntityProgress: Int = (0.5 * nonVerificationEntityProgress).round.toInt

  private var totalEntities: Int = 0

  private def verificationEntityProgress: Int =
    ((100 * verificationFraction) * (if (totalEntities == 0) 1 else 1.0 / totalEntities)).round.toInt

  
  private def updateProgress(update: Int): Unit = {
    progress += update
    VerifierState.updateVerificationInformation(fileUris, Left(progress))
  }

  private def write(inputs: Vector[String], fileExt: String, content: String): Unit = {
    // this message belongs to multiple inputs. We simply pick the first one for the resulting file's name
    Violation.violation(inputs.nonEmpty, s"expected at least one file path for which the following message was reported: '$content''")
    write(inputs.head, fileExt, content)
  }

  private def write(input: String, fileExt: String, content: String): Unit = {
    val outputFile = OutputUtil.postfixFile(Paths.get(input), fileExt)
    try {
      FileUtils.writeStringToFile(outputFile.toFile, content, UTF_8)
    } catch {
      case _: UnsupportedOperationException => println(s"cannot write output to file $outputFile")
    }
  }


  /**
    * State and helper functions used for result streaming.
    */
  private val reportedErrors = mutable.Set[VerifierError]()

  private def getFileType(path: String): FileType.Value = if (path.endsWith(".gobra")) FileType.Gobra else FileType.Go

  private def errorToDiagnostic(error: VerifierError): Diagnostic = {
    val fileType = error.position.map(pos => getFileType(pos.file.toString)).getOrElse(FileType.Gobra)
    val startPos = new Position(
      error.position.get.start.line - 1,
      if (fileType == FileType.Gobra) error.position.get.start.column - 1 else 0
    )
    val endPos = error.position.get.end match {
      case Some(pos) => new Position(
        pos.line - 1,
        if (fileType == FileType.Gobra) pos.column - 1 else Int.MaxValue
      )
      case None => startPos
    }

    new Diagnostic(new Range(startPos, endPos), error.message, DiagnosticSeverity.Error, "")
  }

  private def updateDiagnostics(result: VerifierResult): Unit = result match {
    case VerifierResult.Success => // ignore
    case VerifierResult.Failure(errs) =>
      val newErrors = errs.filterNot(reportedErrors)
      reportedErrors ++= newErrors

      fileData.foreach(file => updateDiagnosticsPerFile(file, newErrors))
  }

  private def updateDiagnosticsPerFile(file: FileData, newErrors: Vector[VerifierError]) = {
    val errorsInFile = newErrors.filter(err => err.position.exists(pos => pos.file.toString == file.filePath))
    val cachedErrors = errorsInFile.filter(_.cached).toList
    val nonCachedErrors = errorsInFile.filterNot(_.cached).toList

    val diagnosticsCache = VerifierState.getDiagnosticsCache(file.fileUri)
    val cachedDiagnostics = cachedErrors.map(err =>
      diagnosticsCache.getOrElse(err, throw GobraServerCacheInconsistentException()))

    val nonCachedDiagnostics = nonCachedErrors.map(err => errorToDiagnostic(err))

    // Filechanges which happened during the verification
    val fileChanges = VerifierState.changes.filter(_._1 == file.fileUri).flatMap(_._2)

    val diagnostics = cachedDiagnostics ++ VerifierState.translateDiagnostics(fileChanges, nonCachedDiagnostics)
    val sortedErrors = cachedErrors ++ nonCachedErrors

    val oldDiagnostics = VerifierState.getDiagnostics(file.fileUri)
    VerifierState.addDiagnostics(file.fileUri, diagnostics ++ oldDiagnostics)

    VerifierState.publishDiagnostics(file.fileUri)

    if (backend == ViperServerBackend) VerifierState.addDiagnosticsCache(file.fileUri, sortedErrors, diagnostics)
  }

  private def finishedVerification() : Unit = {
    VerifierState.verificationRunning -= 1
    // remove all changes belonging to one of the files in `fileUris`:
    VerifierState.changes = VerifierState.changes.filter(change => !fileUris.contains(change._1))

    val result = if (reportedErrors.isEmpty) VerifierResult.Success else VerifierResult.Failure(reportedErrors.toVector)

    val endTime = System.currentTimeMillis()
    val overallResult = Helper.getOverallVerificationResult(fileUris, result, endTime - startTime)
    VerifierState.updateVerificationInformation(fileUris, Right(overallResult))
  }

  /**
    * Function handling the reports arriving from the verification.
    */
  override def report(msg: GobraMessage): Unit = {
    logger.get.trace(s"GobraIdeReport has received message $msg")
    msg match {
      case CopyrightReport(text) => println(text)

      case PreprocessedInputMessage(_, _) => updateProgress(preprocessEntityProgress)

      case ParsedInputMessage(input, program) =>
        updateProgress(preprocessEntityProgress)
        if (unparse) write(input, "unparsed", program().formatted)

      case ParserErrorMessage(_, result) =>
        updateDiagnostics(VerifierResult.Failure(result))
        finishedVerification()

      case TypeCheckSuccessMessage(inputs, _, erasedGhostCode, goifiedGhostCode) =>
        updateProgress(nonVerificationEntityProgress)
        if (eraseGhost) write(inputs, "ghostLess", erasedGhostCode())
        if (goify) write(inputs, "go", goifiedGhostCode())

      case TypeCheckFailureMessage(_, _, _, result) =>
        updateDiagnostics(VerifierResult.Failure(result))
        finishedVerification()

      case TypeCheckDebugMessage(inputs, _, debugTypeInfo) if debug => write(inputs, "debugType", debugTypeInfo())

      case DesugaredMessage(inputs, internal) =>
        updateProgress(nonVerificationEntityProgress)
        if (printInternal) write(inputs, "internal", internal().formatted)

      case m@GeneratedViperMessage(inputs, ast, backtrack) =>
        updateProgress(nonVerificationEntityProgress)
        if (printVpr){
          write(inputs, "vpr", m.vprAstFormatted)
        }
        // submit the Viper AST's verification to the thread pool:
        VerifierState.submitVerificationJob(ast(), backtrack(), startTime, verifierConfig)(executor)

      case GobraOverallSuccessMessage(_) =>
        fileUris.foreach(fileUri => {
          VerifierState.removeDiagnostics(fileUri)
          VerifierState.publishDiagnostics(fileUri)
          finishedVerification()
        })

      case GobraOverallFailureMessage(_, result) =>
        updateDiagnostics(result)
        finishedVerification()

      case GobraEntitySuccessMessage(_, _) => updateProgress(verificationEntityProgress)

      case GobraEntityFailureMessage(_, _, result) =>
        updateProgress(verificationEntityProgress)
        updateDiagnostics(result)

      case RawMessage(m) => m match {
        case StatisticsReport(nOfMethods, nOfFunctions, nOfPredicates, _, _) =>
          totalEntities = nOfMethods + nOfFunctions + nOfPredicates

        case _ => // ignore
      }

      case _ => // ignore
    }
  }
}