package viper.gobraserver

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8

import org.apache.commons.io.FileUtils
import org.eclipse.lsp4j.{Diagnostic, DiagnosticSeverity, Position, Range}
import viper.gobra.backend.{ViperBackend, ViperBackends}
import viper.gobra.reporting._
import viper.gobra.util.OutputUtil
import viper.silver.reporter.StatisticsReport

import scala.collection.mutable

case class GobraIdeReporter(name: String = "gobraide_reporter",
                            startTime: Long,
                            verifierConfig: VerifierConfig,
                            fileUri: String,
                            backend: ViperBackend,
                            verificationFraction: Double,
                            var progress: Int,
                            unparse: Boolean = false,
                            eraseGhost: Boolean = false,
                            goify: Boolean = false,
                            debug: Boolean = false,
                            printInternal: Boolean = false,
                            printVpr: Boolean = false) extends GobraReporter {

  /**
    * State and Helper functions used for tracking the progress of the Verification.
    */
  private def nonVerificationEntityProgress: Int = ((1 - verificationFraction) * 25).round.toInt
  private def preprocessEntityProgress: Int = (0.5 * nonVerificationEntityProgress).round.toInt

  private val finishedProgress: Int = 100
  private var totalEntities: Int = 0

  private def verificationEntityProgress: Int =
    ((100 * verificationFraction) * (if (totalEntities == 0) 1 else 1.0 / totalEntities)).round.toInt

  
  private def updateProgress(update: Int): Unit = {
    progress += update
    VerifierState.updateVerificationInformation(fileUri, Left(progress))
  }

  private def write(file: File, fileExt: String, content: String): Unit = {
    val outputFile = OutputUtil.postfixFile(file, fileExt)
    FileUtils.writeStringToFile(outputFile, content, UTF_8)
  }


  /**
    * State and helper functions used for result streaming.
    */
  private val reportedErrors = mutable.Set[VerifierError]()

  private def fileType = if (fileUri.endsWith(".gobra")) FileType.Gobra else FileType.Go

  private def errorToDiagnostic(error: VerifierError): Diagnostic = {
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

      val cachedErrors = newErrors.filter(_.cached).toList
      val nonCachedErrors = newErrors.filterNot(_.cached).toList

      val diagnosticsCache = VerifierState.getDiagnosticsCache(fileUri)
      val cachedDiagnostics = cachedErrors.map(err =>
        diagnosticsCache.getOrElse(err, throw GobraServerCacheInconsistentException()))

      val nonCachedDiagnostics = nonCachedErrors.map(err => errorToDiagnostic(err))

      // Filechanges which happened during the verification
      val fileChanges = VerifierState.changes.filter(_._1 == fileUri).flatMap(_._2)

      val diagnostics = cachedDiagnostics ++ VerifierState.translateDiagnostics(fileChanges, nonCachedDiagnostics)
      val sortedErrors = cachedErrors ++ nonCachedErrors

      val oldDiagnostics = VerifierState.getDiagnostics(fileUri)
      VerifierState.addDiagnostics(fileUri, diagnostics ++ oldDiagnostics)

      if (fileUri == VerifierState.openFileUri) VerifierState.publishDiagnostics(fileUri)

      if (backend == ViperBackends.ViperServerBackend) VerifierState.addDiagnosticsCache(fileUri, sortedErrors, diagnostics)

  }

  private def finishedVerification() : Unit = {
    VerifierState.verificationRunning -= 1
    VerifierState.changes = VerifierState.changes.filter(_._1 != fileUri)

    val result = if (reportedErrors.isEmpty) VerifierResult.Success else VerifierResult.Failure(reportedErrors.toVector)

    val endTime = System.currentTimeMillis()
    val overallResult = Helper.getOverallVerificationResult(fileUri, result, endTime - startTime)
    VerifierState.updateVerificationInformation(fileUri, Right(overallResult))
  }

  /**
    * Function handling the reports arriving from the verification.
    */
  override def report(msg: GobraMessage): Unit = msg match {
    case CopyrightReport(text) => println(text)

    case PreprocessedInputMessage(_, _) => updateProgress(preprocessEntityProgress)

    case ParsedInputMessage(file, program) =>
      updateProgress(preprocessEntityProgress)
      if (unparse) write(file, "unparsed", program().formatted)

    case ParserErrorMessage(_, result) =>
      updateDiagnostics(VerifierResult.Failure(result))
      finishedVerification()

    case TypeCheckSuccessMessage(file, _, erasedGhostCode, goifiedGhostCode) =>
      updateProgress(nonVerificationEntityProgress)
      if (eraseGhost) write(file, "ghostLess", erasedGhostCode())
      if (goify) write(file, "go", goifiedGhostCode())

    case TypeCheckFailureMessage(_, _, _, result) =>
      updateDiagnostics(VerifierResult.Failure(result))
      finishedVerification()

    case TypeCheckDebugMessage(file, _, debugTypeInfo) if debug => write(file, "debugType", debugTypeInfo())

    case DesugaredMessage(file, internal) =>
      updateProgress(nonVerificationEntityProgress)
      if (printInternal) write(file, "internal", internal().formatted)

    case m@GeneratedViperMessage(file, ast, backtrack) =>
      updateProgress(nonVerificationEntityProgress)
      if (printVpr) write(file, "vpr", m.vprAstFormatted)

      VerifierState.jobQueue.synchronized {
        VerifierState.jobQueue.enqueue((ast, backtrack, startTime, verifierConfig))
        VerifierState.jobQueue.notify()
      }

    
    case GobraOverallSuccessMessage(_) =>
      VerifierState.removeDiagnostics(fileUri)
      if (fileUri == VerifierState.openFileUri) VerifierState.publishDiagnostics(fileUri)
      finishedVerification()

    case GobraOverallFailureMessage(_, result) =>
      updateDiagnostics(result)
      finishedVerification()

    case GobraEntitySuccessMessage(_, _) => updateProgress(verificationEntityProgress)

    case GobraEntityFailureMessage(_, _, result) =>
      updateProgress(verificationEntityProgress)
      updateDiagnostics(result)

    case RawMessage(m) => m match {
      case StatisticsReport(nOfMethods, nOfFunctions, nOfPredicates, nOfDomains, nOfFields) =>
        totalEntities = nOfMethods + nOfFunctions + nOfPredicates

      case _ => // ignore
    }

    case _ => // ignore
  }
}