// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import com.google.gson.Gson
import viper.gobra.Gobra
import viper.gobra.GobraFrontend
import viper.gobra.reporting.VerifierResult
import viper.gobra.util.{GobraExecutionContext, Violation}
import viper.gobra.reporting.BackTranslator.BackTrackInfo
import viper.silver.ast.Program
import viper.server.core.ViperCoreServer
import org.eclipse.lsp4j.{MessageParams, MessageType, Range}
import viper.gobra.frontend.{Gobrafier, Parser}
import viper.gobraserver.backend.ViperServerBackend
import viper.server.ViperConfig

import java.io.{BufferedWriter, File, FileWriter}
import scala.concurrent.Future
import scala.io.Source
import scala.util.{Failure, Success}


class GobraServerException extends Exception

case class GobraServerCacheInconsistentException() extends GobraServerException {
  override def toString: String = s"The diagnostics Cache is not consistent with Viper Server Cache."
}


object GobraServer extends GobraFrontend {


  private var _verifier: Gobra = _
  def verifier: Gobra = _verifier

  private var _options: List[String] = List()
  private var _executor: GobraServerExecutionContext = _
  private var _server: ViperCoreServer = _
  private lazy val gson: Gson = new Gson()

  def init(options: List[String])(executor: GobraServerExecutionContext): Unit = {
    _options = options
    _executor = executor
    val config = new ViperConfig(options)
    _server = new ViperCoreServer(config)(executor)
    ViperServerBackend.setExecutor(_executor)
    ViperServerBackend.setServer(_server)
  }

  def start(): Unit = {
    _verifier = new Gobra
    _server.start()
    VerifierState.flushCachedDiagnostics()
  }

  def restart(): Future[Unit] = {
    stop()
      .flatMap(_ => {
        delete()
        _executor.restart()
      })(_executor)
      .map(_ => {
        val options = _options
        val executor = _executor
        init(options)(executor)
        start()
      })(_executor)
  }

  private def serverExceptionHandling(fileData: Vector[FileData], resultFuture: Future[VerifierResult])(implicit executor: GobraExecutionContext): Future[VerifierResult] = {

    val fileUris = fileData.map(_.fileUri)

    // do some post processing if verification has failed
    resultFuture.transformWith {
      case Success(res) =>
        _server.logger.get.trace(s"GobraServer: verification was successful: $res")
        Future.successful(res)
      case Failure(exception) =>
        // restart Gobra Server and then update client state
        // ignore result of restart and inform the client:
        restart().transformWith(_ => {
          exception match {
            case e: Violation.LogicException =>
              fileUris.foreach(VerifierState.removeDiagnostics)
              val overallResult = Helper.getOverallVerificationResultFromException(fileUris, e)

              VerifierState.updateVerificationInformation(fileUris, Right(overallResult))

              fileUris.foreach(VerifierState.publishDiagnostics)

            case e =>
              println("Exception occurred:")
              e.printStackTrace()

              // remove verification information about this file
              // otherwise, reopening this file in the client will result in sending the last progress although no
              // verification is running
              VerifierState.removeVerificationInformation(fileUris)

              VerifierState.client match {
                case Some(c) =>
                  c.showMessage(new MessageParams(MessageType.Error, "An exception occurred during verification: " + e))
                  val encodedFileUris = gson.toJson(fileUris.toArray)
                  c.verificationException(encodedFileUris)
                case None =>
              }
          }
          // forward original result
          Future.failed(exception)
        })
    }
  }

  /**
    * Preprocess file and enqueue the Viper AST whenever it is created.
    */
  def preprocess(verifierConfig: VerifierConfig)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    val fileUris = verifierConfig.fileData.map(_.fileUri)

    VerifierState.verificationRunning += 1
    fileUris.foreach(VerifierState.removeDiagnostics)

    val startTime = System.currentTimeMillis()

    val config = Helper.verificationConfigFromTask(verifierConfig, startTime, verify = false, logger = _server.logger)(executor)
    val preprocessFuture = verifier.verify(config)(executor)

    serverExceptionHandling(verifierConfig.fileData.toVector, preprocessFuture)
  }

  /**
    * Verify Viper AST.
    */
  def verify(verifierConfig: VerifierConfig, ast: Program, backtrack: BackTrackInfo, startTime: Long)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    val completedProgress = (100 * (1 - Helper.defaultVerificationFraction)).toInt
    val config = Helper.verificationConfigFromTask(verifierConfig, startTime, verify = true, completedProgress, logger = _server.logger)(executor)

    val resultFuture = verifier.verifyAst(config, ast, backtrack)(executor)

    serverExceptionHandling(verifierConfig.fileData.toVector, resultFuture)
  }

  /**
    * Goify File and publish potential errors as Diagnostics.
    */
  def goify(fileData: FileData)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    val fileUri = fileData.fileUri
    val config = Helper.goifyConfigFromTask(fileData)
    val goifyFuture = verifier.verify(config)(executor)

    goifyFuture.onComplete {
      case Success(result) =>
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
    VerifierState.removeVerificationInformation(Vector(newFileUri))

    if (newFileUri == VerifierState.openFileUri) VerifierState.publishDiagnostics(newFileUri)

    try {
      val fileBuffer = Source.fromFile(filePath)
      val fileContents = fileBuffer.mkString
      fileBuffer.close()
      
      val gobraFile = new File(newFilePath)
      val bw = new BufferedWriter(new FileWriter(gobraFile))

      bw.write(Gobrafier.gobrafy(fileContents))
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
  def codePreview(fileData: Vector[FileData], internalPreview: Boolean, viperPreview: Boolean, selections: List[Range])(executor: GobraExecutionContext): Future[VerifierResult] = {
    val config = Helper.previewConfigFromTask(fileData, internalPreview, viperPreview, selections)
    verifier.verify(config)(executor)
  }


  def stop(): Future[Unit] = {
    _server.stop().map(_ => ())(_executor)
  }

  def flushCache(): Unit = {
    // flush Gobra's parser cache:
    Parser.flushCache()
    _server.flushCache()
    VerifierState.flushCachedDiagnostics()
    VerifierState.changes = List()
  }

  def delete(): Unit = {
    ViperServerBackend.resetServer()
    ViperServerBackend.resetExecutor()
    _server = null
  } 
}
