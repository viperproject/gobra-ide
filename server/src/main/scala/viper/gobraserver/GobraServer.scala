package viper.gobraserver

import viper.gobra.Gobra
import viper.gobra.GobraFrontend
import viper.gobra.reporting.VerifierResult

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

    resultFuture.onComplete {
      case Success(result) =>
        val endTime = System.currentTimeMillis()
        val diagnostics = result match {
          case VerifierResult.Success => List()
          case VerifierResult.Failure(errors) =>
            errors.map({error =>
              val startPos = new Position(error.position.start.line-1, error.position.start.column-1)
              val endPos = error.position.end match {
                case Some(pos) => new Position(pos.line-1, pos.column-1)
                case None =>
                  startPos
              }
              new Diagnostic(new Range(startPos, endPos), error.message, DiagnosticSeverity.Error, "")
            }).toList


        }
        val overallResult = Helper.getOverallVerificationResult(result, endTime - startTime)

        VerifierState.addDiagnostics(fileUri, diagnostics, overallResult)

        // only send diagnostics after verification if same file is still open.
        if (fileUri == VerifierState.openFileUri) {
          VerifierState.publishDiagnostics(fileUri)
          VerifierState.sendOverallResult(fileUri)
        }
        VerifierState.sendFinishedVerification(fileUri)

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

  def stop() {
    _server.stop()
  }

  def delete() {
    ViperBackends.ViperServerBackend.resetServer()
    _server = null
  } 
}