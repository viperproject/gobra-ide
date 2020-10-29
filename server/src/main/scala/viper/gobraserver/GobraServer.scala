// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

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

  private var _options: List[String] = List()
  private var _server: ViperCoreServer = _

  def init(options: List[String]): Unit = {
    _options = options
    _server = new ViperCoreServer(options.toArray)
    ViperBackends.ViperServerBackend.setServer(_server)
  }

  def start(): Unit = {
    _verifier = new Gobra
    _server.start()
    VerifierState.flushCachedDiagnostics()
  }

  def restart(): Unit = {
    stop()
    delete()
    val options = _options
    init(options)
    start()
  }

  private def serverExceptionHandling(fileData: FileData, resultFuture: Future[VerifierResult]): Future[VerifierResult] = {

    val fileUri = fileData.fileUri

    // do some post processing if verification has failed
    resultFuture.recoverWith({ case exception =>
      // restart Gobra Server and then update client state
      restart()

      exception match {
        case e: Violation.LogicException =>
          VerifierState.removeDiagnostics(fileUri)
          val overallResult = Helper.getOverallVerificationResultFromException(fileUri, e)

          VerifierState.updateVerificationInformation(fileUri, Right(overallResult))


          if (fileUri == VerifierState.openFileUri) {
            VerifierState.publishDiagnostics(fileUri)
          }

        case e =>
          println("Exception occurred:")
          e.printStackTrace()

          // remove verification information about this file
          // otherwise, reopening this file in the client will result in sending the last progress although no
          // verification is running
          VerifierState.removeVerificationInformation(fileUri)

          VerifierState.client match {
            case Some(c) =>
              c.showMessage(new MessageParams(MessageType.Error, "An exception occurred during verification: " + e))
              c.verificationException(fileUri)
            case None =>
          }
      }

      // forward original result
      Future.failed(exception)
    })
  }

  /**
    * Preprocess file and enqueue the Viper AST whenever it is created.
    */
  def preprocess(verifierConfig: VerifierConfig): Future[VerifierResult] = {
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
  def preprocessGo(verifierConfig: VerifierConfig): Future[VerifierResult] = {
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

    // adapt config to use the temp file instead of the original file containing the Go code
    val tmpConfig = config.copy(inputFiles = Vector(tempFi))
    val verifyAndDeleteTempFile = verifier.verify(tmpConfig)
      .transform(res => {
        // delete the temporary file (in case of success & failure)
        // note that this continuation does not run after the verification but already after desugaring (i.e. before inserting the Viper AST into the queue)
        // delete the temporary file is fine at this point because only the in-memory Viper AST is used for the subsequent steps
        val deleted = tempFi.delete()
        if (!deleted) {
          println(s"Deleting temporary file has failed (file: ${tempFi.getAbsolutePath})")
        }
        res
      })

    serverExceptionHandling(verifierConfig.fileData, verifyAndDeleteTempFile)
  }

  /**
    * Verify Viper AST.
    */
  def verify(verifierConfig: VerifierConfig, ast: () => Program, backtrack: () => BackTrackInfo, startTime: Long): Future[VerifierResult] = {
    val completedProgress = (100 * (1 - Helper.defaultVerificationFraction)).toInt
    val config = Helper.verificationConfigFromTask(verifierConfig, startTime, verify = true, completedProgress)

    val resultFuture = verifier.verifyAst(config, ast(), backtrack())

    serverExceptionHandling(verifierConfig.fileData, resultFuture)
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
  def gobrafy(fileData: FileData): Unit = {
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
  def codePreview(fileData: FileData, internalPreview: Boolean, viperPreview: Boolean, selections: List[Range]): Future[VerifierResult] = {
    val config = Helper.previewConfigFromTask(fileData, internalPreview, viperPreview, selections)
    verifier.verify(config)
  }


  def stop(): Unit = {
    _server.stop()
  }

  def flushCache(): Unit = {
    _server.flushCache()
    VerifierState.flushCachedDiagnostics()
    VerifierState.changes = List()
  }

  def delete(): Unit = {
    ViperBackends.ViperServerBackend.resetServer()
    _server = null
  } 
}
