package viper.gobraserver

import viper.gobra.Gobra
import viper.gobra.GobraFrontend
import viper.gobra.reporting.VerifierResult
import viper.gobra.backend.ViperBackends
import viper.gobra.reporting.VerifierError
import viper.gobra.util.Violation$LogicException
import viper.gobra.frontend.Config

import java.io._
import scala.io.Source

import viper.server.{ ViperCoreServer, ViperConfig }
import viper.gobra.backend.ViperBackends

import org.eclipse.lsp4j.{ Diagnostic, Position, Range, DiagnosticSeverity, PublishDiagnosticsParams, MessageParams, MessageType }

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.util.{ Success, Failure }


class GobraServerException extends Exception

case class GobraServerCacheInconsistentException() extends GobraServerException {
  override def toString: String = s"The diagnostics Cache is not consistent with Viper Server Cache."
}


object GobraServer extends GobraFrontend {
  implicit val executionContext = ExecutionContext.global

  private var _verifier: Gobra = _
  def verifier: Gobra = _verifier

  private var _server: ViperCoreServer = _

  def init(options: List[String]) {
    val config = new ViperConfig(options)

    _server = new ViperCoreServer(config)
    ViperBackends.ViperServerBackend.setServer(_server)
  }

  def start() {
    _verifier = new Gobra
    _server.start()
    VerifierState.flushCachedDiagnostics()
  }


  private def errorToDiagnostic(error: VerifierError, fileType: FileType.Value): Diagnostic = {
    val startPos = new Position(
      error.position.start.line - 1,
      if (fileType == FileType.Gobra) error.position.start.column - 1 else 0
    )

    val endPos = error.position.end match {
      case Some(pos) => new Position(
        pos.line - 1,
        if (fileType == FileType.Gobra) pos.column - 1 else Int.MaxValue
      )
      case None => startPos
    }

    new Diagnostic(new Range(startPos, endPos), error.message, DiagnosticSeverity.Error, "")
  }


  private def displayVerificationResult(fileData: FileData, config: Config, startTime: Long, resultFuture: Future[VerifierResult]) {

    val fileUri = fileData.fileUri
    val filePath = fileData.filePath

    val fileType = if (fileUri.endsWith(".gobra")) FileType.Gobra else FileType.Go

    resultFuture.onComplete {
      case Success(result) =>
        val endTime = System.currentTimeMillis()

        result match {
          case VerifierResult.Success => {
            VerifierState.removeDiagnostics(fileUri)
          }
          case VerifierResult.Failure(errors) =>

            val cachedErrors = errors.filter(_.cached).toList
            val nonCachedErrors = errors.filterNot(_.cached).toList

            val diagnosticsCache = VerifierState.getDiagnosticsCache(fileUri)
            val cachedDiagnostics = cachedErrors.map(err => diagnosticsCache.get(err) match {
              case Some(diagnostic) => diagnostic
              case None =>
                println("Caches not consistent!")
                throw GobraServerCacheInconsistentException()
            }).toList

            val nonCachedDiagnostics = nonCachedErrors.map(err => errorToDiagnostic(err, fileType)).toList

            // Filechanges which happened during the verification.
            val fileChanges = VerifierState.changes.filter({case (uri, _) => uri == fileUri}).flatMap({case (_, change) => change})

            val diagnostics = cachedDiagnostics ++ VerifierState.translateDiagnostics(fileChanges, nonCachedDiagnostics)
            val sortedErrs = cachedErrors ++ nonCachedErrors

            VerifierState.addDiagnostics(fileUri, diagnostics)
            // only update diagnostics cache when ViperServer is used as a backend.
            if (config.backend == ViperBackends.ViperServerBackend) VerifierState.addDiagnosticsCache(fileUri, sortedErrs, diagnostics)
        }
        VerifierState.verificationRunning = false
        // remove all filechanges associated to this file which occured during the verification.
        VerifierState.changes = VerifierState.changes.filter({case (uri, _) => uri != fileUri})
        
        val overallResult = Helper.getOverallVerificationResult(fileUri, result, endTime - startTime)
        VerifierState.updateVerificationInformation(fileUri, Right(overallResult))


        if (fileUri == VerifierState.openFileUri) VerifierState.publishDiagnostics(fileUri)

      case Failure(exception) =>

        exception match {
          case e: Violation$LogicException => {
            VerifierState.removeDiagnostics(fileUri)
            val overallResult = Helper.getOverallVerificationResultFromException(fileUri, e)

            VerifierState.updateVerificationInformation(fileUri, Right(overallResult))


            if (fileUri == VerifierState.openFileUri) VerifierState.publishDiagnostics(fileUri)
          }
          case e => {
            println("Exception occured: " + e)
            VerifierState.client match {
              case Some(c) =>
                c.showMessage(new MessageParams(MessageType.Error, "An exception occured during verification: " + e))
                c.verificationException(fileUri)
              case None =>
            }
          }
        }

        // restart GobraServer
        println("Restarting Gobra Server")
        start()
    }
  }

  /**
    * Verify file and display potential errors as Diagnostics.
    */
  def verify(verifierConfig: VerifierConfig): Future[VerifierResult] = {
    val fileUri = verifierConfig.fileData.fileUri
    val filePath = verifierConfig.fileData.filePath

    VerifierState.verificationRunning = true
    
    val startTime = System.currentTimeMillis()

    val config = Helper.verificationConfigFromTask(verifierConfig, startTime)
    val resultFuture = verifier.verify(config)

    displayVerificationResult(verifierConfig.fileData, config, startTime, resultFuture)
 

    resultFuture
  }


  /**
    * Goify File and publish potential errors as Diagnostics.
    */
  def goify(fileData: FileData): Future[VerifierResult] = {
    val fileUri = fileData.fileUri

    val filePath = fileData.filePath
    val startTime = System.currentTimeMillis()

    val config = Helper.goifyConfigFromTask(fileData)

    val goifyFuture = verifier.verify(config)

    goifyFuture.onComplete {
      case Success(result) =>
        val endTime = System.currentTimeMillis()

        (result, VerifierState.client) match {
          case (VerifierResult.Success, Some(c)) =>
            c.finishedGoifying(fileUri, true)
          case (VerifierResult.Failure(_), Some(c)) =>
            c.finishedGoifying(fileUri, false)
          case _ =>
        }
      
      case Failure(_) =>
        VerifierState.client match {
          case Some(c) => c.finishedGoifying(fileUri, false)
          case None =>
        }
    }

    goifyFuture
  }


  /**
    * Gobrafy File.
    */
  def gobrafy(fileData: FileData) {
    var success = false

    val filePath = fileData.filePath
    val fileUri = fileData.fileUri

    val newFilePath = Helper.gobraFileExtension(filePath)
    val newFileUri = Helper.gobraFileExtension(fileUri)

    VerifierState.removeDiagnostics(newFileUri)
    VerifierState.removeVerificationInformation(newFileUri)

    if (newFileUri == VerifierState.openFileUri) VerifierState.publishDiagnostics(newFileUri)

    try {
      val fileContents = Source.fromFile(filePath).mkString
      
      val gobraFile = new File(newFilePath)
      val bw = new BufferedWriter(new FileWriter(gobraFile))

      bw.write(GobrafierRunner.gobrafyFileContents(fileContents))
      bw.close()

      success = true  
    } catch {
      case _: Throwable => // just fall through case since we were pessimistic with the success.
    }

    VerifierState.client match {
        case Some(c) => c.finishedGobrafying(filePath, newFilePath, success)
        case None =>
    }
  }


  /**
    * Verify Go File directly.
    */
  def verifyGo(verifierConfig: VerifierConfig): Future[VerifierResult] = {
    val filePath = verifierConfig.fileData.filePath
    val fileUri = verifierConfig.fileData.fileUri

    VerifierState.verificationRunning = true

    val fileContents = Source.fromFile(filePath).mkString
    val gobrafiedContents = GobrafierRunner.gobrafyFileContents(fileContents)

    val startTime = System.currentTimeMillis()

    val config = Helper.verificationConfigFromTask(verifierConfig, startTime)
    val resultFuture = verifier.verify(gobrafiedContents, config)

    displayVerificationResult(verifierConfig.fileData, config, startTime, resultFuture)

    resultFuture
  }


  def stop() {
    _server.stop()
  }

  def flushCache() {
    _server.flushCache()
    VerifierState.flushCachedDiagnostics()
    VerifierState.changes = List()
  }

  def delete() {
    ViperBackends.ViperServerBackend.resetServer()
    _server = null
  } 
}