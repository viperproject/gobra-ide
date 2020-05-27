package viper.gobraserver

import viper.gobra.Gobra
import viper.gobra.GobraFrontend
import viper.gobra.reporting.VerifierResult
import viper.gobra.backend.ViperBackends
import viper.gobra.reporting.VerifierError
import viper.gobra.util.Violation$LogicException

import java.io.File

import scala.concurrent.ExecutionContext
import viper.server.{ ViperCoreServer, ViperConfig }
import viper.gobra.backend.ViperBackends

import org.eclipse.lsp4j.{ Diagnostic, Position, Range, DiagnosticSeverity, PublishDiagnosticsParams, MessageParams, MessageType }

import scala.concurrent.Future
import scala.util.{ Success, Failure }

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

  def verify(verifierConfig: VerifierConfig): Future[VerifierResult] = {
    val fileUri = verifierConfig.fileData.fileUri

    VerifierState.toggleVerificationRunning
    
    val filePath = verifierConfig.fileData.filePath
    val startTime = System.currentTimeMillis()

    val config = Helper.configFromTask(verifierConfig)
    val resultFuture = verifier.verify(config)

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
                errorToDiagnostic(err)
            }).toList

            val nonCachedDiagnostics = nonCachedErrors.map(err => errorToDiagnostic(err)).toList

            // Filechanges which happened during the verification.
            val fileChanges = VerifierState.changes.filter({case (uri, _) => uri == fileUri}).flatMap({case (_, change) => change})

            val diagnostics = cachedDiagnostics ++ VerifierState.translateDiagnostics(fileChanges, nonCachedDiagnostics)
            val sortedErrs = cachedErrors ++ nonCachedErrors

            VerifierState.addDiagnostics(fileUri, diagnostics)
            // only update diagnostics cache when ViperServer is used as a backend.
            if (config.backend == ViperBackends.ViperServerBackend) VerifierState.addDiagnosticsCache(fileUri, sortedErrs, diagnostics)
        }
        VerifierState.toggleVerificationRunning
        // remove all filechanges associated to this file which occured during the verification.
        VerifierState.changes = VerifierState.changes.filter({case (uri, _) => uri != fileUri})
        
        val overallResult = Helper.getOverallVerificationResult(result, endTime - startTime)
        VerifierState.addOverallResult(fileUri, overallResult)

        publishResults(fileUri)

      case Failure(exception) =>

        exception match {
          case e: Violation$LogicException => {
            VerifierState.removeDiagnostics(fileUri)
            val overallResult = Helper.getOverallVerificationResultFromException(e)
            VerifierState.addOverallResult(fileUri, overallResult)

            publishResults(fileUri)
          }
          case e => {
            println("Exception occured: " + e)
            VerifierState.client match {
              case Some(c) =>
                c.showMessage(new MessageParams(MessageType.Error, "An exception occured during verification of " + filePath))
                c.verificationException(fileUri)
              case None =>
            }
          }
        }

        // restart GobraServer
        println("Restarting Gobra Server")
        start()
    }

    resultFuture
  }

  def publishResults(fileUri: String) {
    if (fileUri == VerifierState.openFileUri) {
      VerifierState.publishDiagnostics(fileUri)
      VerifierState.sendOverallResult(fileUri)
    }
    Helper.sendFinishedVerification(fileUri)
  }

  def errorToDiagnostic(error: VerifierError): Diagnostic = {
    val startPos = new Position(error.position.start.line-1, error.position.start.column-1)
    val endPos = error.position.end match {
      case Some(pos) => new Position(pos.line-1, pos.column-1)
      case None => startPos
    }
    new Diagnostic(new Range(startPos, endPos), error.message, DiagnosticSeverity.Error, "")
  }

  def stop() {
    _server.stop()
  }

  def flushCache() {
    _server.flushCache()
  }

  def delete() {
    ViperBackends.ViperServerBackend.resetServer()
    _server = null
  } 
}