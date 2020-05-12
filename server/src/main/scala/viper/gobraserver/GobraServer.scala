package viper.gobraserver

import viper.gobra.Gobra
import viper.gobra.GobraFrontend
import viper.gobra.reporting.VerifierResult
import viper.gobra.reporting.VerifierError

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
  }

  def verify(verifierConfig: VerifierConfig): Future[VerifierResult] = {
    val fileUri = verifierConfig.fileData.fileUri
    val filePath = verifierConfig.fileData.filePath
    val startTime = System.currentTimeMillis()

    val config = Helper.configFromTask(verifierConfig)
    val resultFuture = verifier.verify(config)

    VerifierState.incVerificationNum()

    resultFuture.onComplete {
      case Success(result) =>
        val endTime = System.currentTimeMillis()

        result match {
          case VerifierResult.Success => {
            // TODO: change this s.t. diagnostics are only hidden and not removed. (removal should only happen on file close)
            VerifierState.removeDiagnostics(fileUri)
          }
          case VerifierResult.Failure(errors) =>
            val cachedErrors = errors.filter(_.cached)
            val nonCachedErrors = errors.filterNot(_.cached)

            val diagnostics = VerifierState.getDiagnostics(fileUri)

            val (oldCachedErrors, oldCachedDiagnostics) = diagnostics
              .filter({case (k, v) => cachedErrors.contains(k)}).toList.unzip

            // Cached Errors which were not seen before.
            val newCachedErrors = cachedErrors.filterNot(oldCachedErrors.toSet)
            val diagnosticsCache = VerifierState.getDiagnosticsCache(fileUri)

            println("The diagnosticsCache is:")
            println(diagnosticsCache)

            val newCachedDiagnostics = newCachedErrors.map(err => diagnosticsCache.get(err) match {
              case Some(diagnostic) =>
                println("retrieved from the cache")
                diagnostic
              case None => errorToDiagnostic(err)
            })


            //val newCachedDiagnostics = newCachedErrors.map(err => errorToDiagnostic(err))

            val newNonCachedDiagnostics = nonCachedErrors.map(err => errorToDiagnostic(err))

            val newErrors = oldCachedErrors ++ newCachedErrors ++ nonCachedErrors
            val newDiagnostics = oldCachedDiagnostics ++ newCachedDiagnostics ++ newNonCachedDiagnostics

            VerifierState.addDiagnostics(fileUri, newErrors, newDiagnostics)
            VerifierState.addDiagnosticsCache(fileUri, newErrors, newDiagnostics)
            
        }
        
        val overallResult = Helper.getOverallVerificationResult(result, endTime - startTime)
        VerifierState.addOverallResult(fileUri, overallResult)


        // only send diagnostics after verification if same file is still open.
        if (fileUri == VerifierState.openFileUri) {
          VerifierState.publishDiagnostics(fileUri)
          VerifierState.sendOverallResult(fileUri)
        }
        Helper.sendFinishedVerification(fileUri)

      case Failure(e) =>
        println("Exception occured: " + e)
        VerifierState.client match {
          case Some(c) =>
            c.showMessage(new MessageParams(MessageType.Error, "An exception occured during verification of " + filePath))
            c.verificationException(fileUri)
          case None =>
        }
    }

    resultFuture
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

  def delete() {
    ViperBackends.ViperServerBackend.resetServer()
    _server = null
  } 
}