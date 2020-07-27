package viper.gobraserver

import viper.gobra.Gobra
import viper.gobra.GobraFrontend
import viper.gobra.reporting.VerifierResult
import viper.gobra.backend.ViperBackends
import viper.gobra.util.Violation$LogicException
import viper.gobra.reporting.BackTranslator.BackTrackInfo

import viper.silver.ast.Program

import java.io._
import scala.io.Source

import viper.server.{ ViperCoreServer, ViperConfig }
import viper.gobra.backend.ViperBackends

import org.eclipse.lsp4j.{ Range, MessageParams, MessageType }

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
  

  private def serverExceptionHandling(fileData: FileData, resultFuture: Future[VerifierResult]) {

    val fileUri = fileData.fileUri
    val filePath = fileData.filePath

    resultFuture.onComplete {
      case Success(result) => // ignore -> handled by reporter

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
    * Preprocess file and enqueue the Viper AST whenever it is created.
    */
  def preprocess(verifierConfig: VerifierConfig): Unit = {
    val fileUri = verifierConfig.fileData.fileUri

    VerifierState.verificationRunning += 1
    VerifierState.removeDiagnostics(fileUri)

    val startTime = System.currentTimeMillis()

    val config = Helper.verificationConfigFromTask(verifierConfig, startTime, false)
    val preprocessFuture = verifier.verify(config)

    serverExceptionHandling(verifierConfig.fileData, preprocessFuture)
  }

  /**
    * Preprocess go file and enqueue the Viper AST whenever it is created.
    */
  def preprocessGo(verifierConfig: VerifierConfig): Unit = {
    val fileUri = verifierConfig.fileData.fileUri
    val filePath = verifierConfig.fileData.filePath

    VerifierState.verificationRunning += 1
    VerifierState.removeDiagnostics(fileUri)

    val fileContents = Source.fromFile(filePath).mkString
    val gobrafiedContents = GobrafierRunner.gobrafyFileContents(fileContents)

    println(gobrafiedContents)

    val startTime = System.currentTimeMillis()

    val config = Helper.verificationConfigFromTask(verifierConfig, startTime, false)
    val preprocessFuture = verifier.verify(gobrafiedContents, config)

    serverExceptionHandling(verifierConfig.fileData, preprocessFuture)
  }

  /**
    * Verify Viper AST.
    */
  def verify(verifierConfig: VerifierConfig, ast: () => Program, backtrack: () => BackTrackInfo, startTime: Long): Future[VerifierResult] = {
    val completedProgress = (100 * (1 - Helper.defaultVerificationFraction)).toInt
    val config = Helper.verificationConfigFromTask(verifierConfig, startTime, true, completedProgress)

    val resultFuture = verifier.verifyAst(config, ast(), backtrack())

    serverExceptionHandling(verifierConfig.fileData, resultFuture)
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
    //val fileUri = fileData.fileUri

    val newFilePath = Helper.gobraFileExtension(filePath)
    val newFileUri = Helper.gobraFileExtension(fileData.fileUri)

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
    * Get preview of Code which then gets displayed on the client side.
    * Currently the internal representation and the viper encoding can be previewed.
    */
  def codePreview(fileData: FileData, internalPreview: Boolean, viperPreview: Boolean, selections: List[Range]) {
    val config = Helper.previewConfigFromTask(fileData, internalPreview, viperPreview, selections)
    val previewFuture = verifier.verify(config)
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