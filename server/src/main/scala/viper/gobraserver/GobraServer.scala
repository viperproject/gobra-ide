package viper.gobraserver

import viper.gobra.Gobra
import viper.gobra.GobraFrontend
import viper.gobra.reporting.VerifierResult
import viper.gobra.util.Violation
import viper.gobra.reporting.BackTranslator.BackTrackInfo
import viper.silver.ast.Program
import java.io._
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.io.Source
import viper.server.core.ViperCoreServer
import viper.gobra.backend.ViperBackends
import org.eclipse.lsp4j.{MessageParams, MessageType, Range}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


class GobraServerException extends Exception

case class GobraServerCacheInconsistentException() extends GobraServerException {
  override def toString: String = s"The diagnostics Cache is not consistent with Viper Server Cache."
}


object GobraServer extends GobraFrontend {
  implicit val executionContext: ExecutionContext = ExecutionContext.global

  private var _verifier: Gobra = _
  def verifier: Gobra = _verifier

  private var _server: ViperCoreServer = _

  def init(options: List[String]) {
    _server = new ViperCoreServer(options.toArray)
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
      case Success(_) => // ignore -> handled by reporter

      case Failure(exception) =>

        exception match {
          case e: Violation.LogicException =>
            VerifierState.removeDiagnostics(fileUri)
            val overallResult = Helper.getOverallVerificationResultFromException(fileUri, e)

            VerifierState.updateVerificationInformation(fileUri, Right(overallResult))


            if (fileUri == VerifierState.openFileUri) VerifierState.publishDiagnostics(fileUri)

          case e =>
            println("Exception occurred:")
            e.printStackTrace()

            VerifierState.client match {
              case Some(c) =>
                c.showMessage(new MessageParams(MessageType.Error, "An exception occurred during verification: " + e))
                c.verificationException(fileUri)
              case None =>
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

    val config = Helper.verificationConfigFromTask(verifierConfig, startTime, verify = false)
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

    val fileBuffer = Source.fromFile(filePath)
    val fileContents = fileBuffer.mkString
    fileBuffer.close()
    val gobrafiedContents = Gobrafier.gobrafyFileContents(fileContents)

    println(gobrafiedContents)

    val startTime = System.currentTimeMillis()

    val config = Helper.verificationConfigFromTask(verifierConfig, startTime, verify = false)

    val tempFileName = s"gobrafiedProgram_${DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm").format(LocalDateTime.now)}"
    val tempFi = File.createTempFile(tempFileName, ".gobra")
    new PrintWriter(tempFi) {
      try {
        write(gobrafiedContents)
      } finally {
        close()
      }
    }

    // FIXME: use temporary file for verification
    val preprocessFuture = verifier.verify(config)

    serverExceptionHandling(verifierConfig.fileData, preprocessFuture)
  }

  /**
    * Verify Viper AST.
    */
  def verify(verifierConfig: VerifierConfig, ast: () => Program, backtrack: () => BackTrackInfo, startTime: Long): Future[VerifierResult] = {
    val completedProgress = (100 * (1 - Helper.defaultVerificationFraction)).toInt
    val config = Helper.verificationConfigFromTask(verifierConfig, startTime, verify = true, completedProgress)

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
            c.finishedGoifying(fileUri, success = true)
          case (VerifierResult.Failure(_), Some(c)) =>
            c.finishedGoifying(fileUri, success = false)
          case _ =>
        }
      
      case Failure(_) =>
        VerifierState.client match {
          case Some(c) => c.finishedGoifying(fileUri, success = false)
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
      val fileBuffer = Source.fromFile(filePath)
      val fileContents = fileBuffer.mkString
      fileBuffer.close()
      
      val gobraFile = new File(newFilePath)
      val bw = new BufferedWriter(new FileWriter(gobraFile))

      bw.write(Gobrafier.gobrafyFileContents(fileContents))
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