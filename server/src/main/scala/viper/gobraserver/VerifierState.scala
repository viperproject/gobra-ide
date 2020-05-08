package viper.gobraserver

import com.google.gson.Gson

import org.eclipse.lsp4j.{
    Diagnostic,
    Position,
    Range,
    DiagnosticSeverity,
    PublishDiagnosticsParams
}

import scala.collection.mutable.Map
import scala.collection.mutable.ListBuffer
import collection.JavaConverters._

import scala.concurrent.Await
import scala.concurrent.duration.Duration


object VerifierState {
  private val gson: Gson = new Gson()

  var openFileUri: String = _

  private var _client: Option[IdeLanguageClient] = None
  def client: Option[IdeLanguageClient] = _client

  def setClient(client: IdeLanguageClient): Unit = {
    _client = Some(client)
  }
  

  // Diagnostics mapping from file uri to a list of diagnostics and overall verification results
  private val _diagnostics = Map[String, (List[Diagnostic], OverallVerificationResult)]()

  def getDiagnostics(fileUri: String): List[Diagnostic] = {
    _diagnostics.get(fileUri) match {
      case Some((diagnostics, _)) => diagnostics
      case None => Nil
    }
  }

  def getOverallResult(fileUri: String): Option[OverallVerificationResult] = {
    _diagnostics.get(fileUri) match {
      case Some((_, overallResult)) => Some(overallResult)
      case None => None
    }
  }

  def resetDiagnostics(fileUri: String) {
    _diagnostics.get(fileUri) match {
      case Some(_) => _diagnostics.remove(fileUri)
      case None =>
    }
  }

  def addDiagnostics(fileUri: String, diagnostics: List[Diagnostic], overallResult: OverallVerificationResult) {
    _diagnostics += (fileUri -> (diagnostics, overallResult))
  }

  def publishDiagnostics(fileUri: String) {
    client match {
      case Some(c) =>
        val params = new PublishDiagnosticsParams(fileUri, getDiagnostics(fileUri).asJava)
        c.publishDiagnostics(params)
      case None =>
    }
  }

  def sendOverallResult(fileUri: String) {
    client match {
      case Some(c) =>
        getOverallResult(fileUri) match {
          case Some(overallResult) => c.overallResultNotification(gson.toJson(overallResult))
          case None => c.noVerificationResult()
        }
    }
  }

  def sendFinishedVerification(fileUri: String) {
    client match {
      case Some(c) => c.finishedVerification(fileUri)
      case None =>
    }
  }
}
