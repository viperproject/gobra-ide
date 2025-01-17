// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import ch.qos.logback.classic.Logger

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import org.apache.commons.io.FileUtils
import org.eclipse.lsp4j.{Diagnostic, DiagnosticSeverity, Position, Range}
import viper.gobra.reporting._
import viper.gobra.util.{GobraExecutionContext, OutputUtil, Violation}
import viper.silver.{ast => vpr}

import scala.collection.mutable

trait VerificationFinishNotifier {
  /** informs clients of GobraServer that verification is done */
  def notifyOverallVerificationFinished(res: VerifierResult, ast: Option[vpr.Program]): Unit
}

/**
  * There is a GobraIdeReporter per verification unit, i.e. a set of files that are verified together. This can be
  * the files belonging to the same package.
  * Note that there are two different reporters involved for a verification: one during parsing, type-checking,
  * desugaring, and encoding and a separate one for the actual verification of the Viper program. As a consequence,
  * `progress` and `ast` are used to pass information to the second reporter such that it can continue where the other
  * left off. `progress` is a value between 0 and 100.
  */
case class GobraIdeReporter(name: String = "gobraide_reporter",
                            startTime: Long,
                            verifierConfig: VerifierConfig,
                            fileData: Vector[FileData],
                            verificationFraction: Double,
                            var progress: Double,
                            ast: Option[vpr.Program],
                            unparse: Boolean = false,
                            eraseGhost: Boolean = false,
                            goify: Boolean = false,
                            debug: Boolean = false,
                            printInternal: Boolean = false,
                            printVpr: Boolean = false,
                            submitAstJob: Boolean = true,
                            cacheDiagnostics: Boolean = false,
                            logger: Logger)(executor: GobraExecutionContext) extends GobraReporter with VerificationFinishNotifier {

  require(fileData.nonEmpty)

  private lazy val fileUris: Vector[String] = fileData.map(_.fileUri)
  private lazy val filePaths: Vector[String] = fileUris.map(Helper.uri2Path(_).toString)
  /** stores the Viper AST that the encoding produces */
  private var generatedAst: Option[vpr.Program] = None
  /** returns the AST passed to the reporter or the one generated */
  private def getAst: Option[vpr.Program] = ast.orElse(generatedAst)

  /**
    * State and Helper functions used for tracking the progress of the Verification.
    */
  private lazy val nonVerificationFraction: Double = 1 - verificationFraction
  // we assume the following non-verification steps (with weights)
  // - preprocess (0.125)
  // - parse (0.125)
  // - typecheck (0.25)
  // - desugar (0.25)
  // - encode (0.25)
  // note however that preprocess and parse happens per source object
  // also, all steps are invoked for imported files for which no progress should be reported
  private lazy val preprocessEntityProgress: Double = 0.125 * nonVerificationFraction / fileData.length * 100
  private lazy val parseEntityProgress: Double = preprocessEntityProgress
  private lazy val typeCheckEntityProgress: Double = 0.25 * nonVerificationFraction * 100
  private lazy val desugarEntityProgress: Double = typeCheckEntityProgress
  private lazy val encodeEntityProgress: Double = typeCheckEntityProgress

  /** Viper members that should be considered for reporting progress */
  private val relevantVprMembers: Option[Set[vpr.Member]] = ast.map(_.members.filter {
    case _: vpr.Method | _: vpr.Function | _: vpr.Predicate => true
    case _ => false
  }.toSet)

  private lazy val verificationEntityProgress: Double =
    (100 * verificationFraction) * (if (relevantVprMembers.forall(_.isEmpty)) 1 else 1.0 / relevantVprMembers.get.size)

  private def isRelevantVprMember(m: vpr.Member): Boolean = relevantVprMembers.exists(_.contains(m))

  
  private def updateProgress(update: Double): Unit = {
    progress += update
    var sanitizedProgress = progress.round.toInt
    sanitizedProgress = if (sanitizedProgress < 0) 0 else if (sanitizedProgress > 100) 100 else sanitizedProgress
    VerifierState.updateVerificationInformation(fileUris, Left(sanitizedProgress))
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

  private def updateDiagnosticsPerFile(file: FileData, newErrors: Vector[VerifierError]): Unit = {
    val errorsInFile = newErrors.filter(err => err.position.exists(pos => pos.file == Helper.uri2Path(file.fileUri)))
    val cachedErrors = errorsInFile.filter(_.cached).toList
    val nonCachedErrors = errorsInFile.filterNot(_.cached).toList

    val diagnosticsCache = VerifierState.getDiagnosticsCache(file.fileUri)
    val cachedDiagnostics = cachedErrors.map(err =>
      diagnosticsCache.getOrElse(err, errorToDiagnostic(err)))

    val nonCachedDiagnostics = nonCachedErrors.map(err => errorToDiagnostic(err))

    // Filechanges which happened during the verification
    val fileChanges = VerifierState.changes.filter(_._1 == file.fileUri).flatMap(_._2)

    val diagnostics = cachedDiagnostics ++ VerifierState.translateDiagnostics(fileChanges, nonCachedDiagnostics)
    val sortedErrors = cachedErrors ++ nonCachedErrors

    val oldDiagnostics = VerifierState.getDiagnostics(file.fileUri)
    VerifierState.addDiagnostics(file.fileUri, diagnostics ++ oldDiagnostics)

    VerifierState.publishDiagnostics(file.fileUri)

    if (cacheDiagnostics) {
      VerifierState.addDiagnosticsCache(file.fileUri, sortedErrors, diagnostics)
    }
  }

  private var isFinished: Boolean = false

  def notifyOverallVerificationFinished(result: VerifierResult, ast: Option[vpr.Program]) : Unit = {
    if (isFinished) {
      return
    }
    isFinished = true

    updateDiagnostics(result)

    VerifierState.verificationRunning -= 1
    // remove all changes belonging to one of the files in `fileUris`:
    VerifierState.changes = VerifierState.changes.filter(change => !fileUris.contains(change._1))

    val endTime = System.currentTimeMillis()
    val overallResult = Helper.getOverallVerificationResult(fileUris.toArray, verifierConfig.isolate, ast, result, endTime - startTime)
    VerifierState.updateVerificationInformation(fileUris, Right(overallResult))
  }

  /**
    * Function handling the reports arriving from the verification.
    */
  override def report(msg: GobraMessage): Unit = {
    logger.trace(s"GobraIdeReport has received message $msg")
    msg match {
      case CopyrightReport(text) => println(text)

      case PreprocessedInputMessage(input, _) => if (filePaths.contains(input)) updateProgress(preprocessEntityProgress)

      case ParsedInputMessage(input, program) =>
        if (filePaths.contains(input)) updateProgress(parseEntityProgress)
        if (unparse) write(input, "unparsed", program().formatted)

      case ParserErrorMessage(_, result) =>
        notifyOverallVerificationFinished(VerifierResult.Failure(result), getAst)

      case TypeCheckSuccessMessage(inputs, _, _, _, erasedGhostCode, goifiedGhostCode) =>
        if (filePaths == inputs) updateProgress(typeCheckEntityProgress)
        if (eraseGhost) write(inputs, "ghostLess", erasedGhostCode())
        if (goify) write(inputs, "go", goifiedGhostCode())

      case TypeCheckFailureMessage(_, _, _, result) =>
        notifyOverallVerificationFinished(VerifierResult.Failure(result), getAst)

      case TypeCheckDebugMessage(inputs, _, debugTypeInfo) if debug => write(inputs, "debugType", debugTypeInfo())

      case DesugaredMessage(inputs, internal) =>
        if (filePaths == inputs) updateProgress(desugarEntityProgress)
        if (printInternal) write(inputs, "internal", internal().formatted)

      case m@GeneratedViperMessage(_, inputs, ast, backtrack) =>
        generatedAst = Some(ast()) // we implicitly assume here that this message is only emitted once per invocation of Gobra
        if (filePaths == inputs) updateProgress(encodeEntityProgress)
        if (printVpr) write(inputs, "vpr", m.vprAstFormatted)
        if (submitAstJob) {
          // this is the old behavior where we split the generation of the Viper AST from its verification
          // submit the Viper AST's verification to the thread pool:
          VerifierState.submitVerificationJob(ast(), backtrack(), startTime, progress.round.toInt, verifierConfig)(executor)
        }

      case GobraOverallSuccessMessage(_) =>
        fileUris.foreach(fileUri => {
          VerifierState.removeDiagnostics(fileUri)
          VerifierState.publishDiagnostics(fileUri)
        })
        notifyOverallVerificationFinished(VerifierResult.Success, getAst)

      case GobraOverallFailureMessage(_, result) =>
        notifyOverallVerificationFinished(result, getAst)

      case GobraEntitySuccessMessage(_, _, member, _, _, _) =>
        if (isRelevantVprMember(member)) updateProgress(verificationEntityProgress)

      case GobraEntityFailureMessage(_, _, member, _, result, _, _) =>
        if (isRelevantVprMember(member)) updateProgress(verificationEntityProgress)
        updateDiagnostics(result)

      case _ => // ignore
    }
  }
}