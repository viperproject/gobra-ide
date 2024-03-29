// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import com.google.gson.Gson
import viper.gobra.Gobra
import viper.gobra.GobraFrontend
import viper.gobra.reporting.{ConfigError, NotFoundError, VerifierResult}
import viper.gobra.util.{GobraExecutionContext, Violation}
import viper.gobra.reporting.BackTranslator.BackTrackInfo
import viper.silver.ast.Program
import viper.server.core.ViperCoreServer
import org.eclipse.lsp4j.{MessageParams, MessageType, Range}
import viper.gobra.frontend.{Config, Gobrafier, Parser, RawConfig}
import viper.server.ViperConfig
import viper.server.vsi.DefaultVerificationServerStart

import java.io.{BufferedWriter, File, FileWriter}
import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.io.Source
import scala.util.{Failure, Success}


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
    _server = new ViperCoreServer(config)(executor) with DefaultVerificationServerStart
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

  private def serverExceptionHandling(fileData: Array[FileData], isolate: Array[IsolationData], ast: Option[Program], resultFuture: Future[VerifierResult])(implicit executor: GobraExecutionContext): Future[VerifierResult] = {

    val fileUris = fileData.map(_.fileUri)

    // do some post processing if verification has failed
    resultFuture.transformWith {
      case Success(res) =>
        _server.globalLogger.trace(s"GobraServer: Gobra handled request successfully: $res")
        Future.successful(res)
      case Failure(exception) =>
        // restart Gobra Server and then update client state
        // ignore result of restart and inform the client:
        restart().transformWith(_ => {
          exception match {
            case e: Violation.LogicException =>
              fileUris.foreach(VerifierState.removeDiagnostics)
              val overallResult = Helper.getOverallVerificationResultFromException(fileUris, isolate, ast, e)

              VerifierState.updateVerificationInformation(fileUris.toVector, Right(overallResult))

              fileUris.foreach(VerifierState.publishDiagnostics)

            case e =>
              println("Exception occurred:")
              e.printStackTrace()

              // remove verification information about this file
              // otherwise, reopening this file in the client will result in sending the last progress although no
              // verification is running
              VerifierState.removeVerificationInformation(fileUris.toVector)

              VerifierState.client match {
                case Some(c) =>
                  c.showMessage(new MessageParams(MessageType.Error, "An exception occurred during execution of Gobra: " + e))
                  val encodedFileUris = gson.toJson(fileUris)
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

    val fileModeConfig = Helper.getFileModeConfig(_server, verifierConfig, startTime, stopAfterEncoding = true)(executor)
    verify(verifierConfig.fileData, verifierConfig.isolate, fileModeConfig)
  }

  def verify(fileData: Array[FileData], isolate: Array[IsolationData], rawConfig: RawConfig)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    rawConfig.config match {
      case Right(config) => verify(fileData, isolate, config)
      case Left(errMsg) => successful(VerifierResult.Failure(Vector(ConfigError(errMsg))))
    }
  }

  /**
    * Wrapper around invoking Gobra with a particular config. Assumes that the config only holds a single package to be
    * verified, otherwise returns a verification failure.
    */
  def verify(fileData: Array[FileData], isolate: Array[IsolationData], config: Config)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    if (config.packageInfoInputMap.keys.size != 1) {
      successful(VerifierResult.Failure(Vector(NotFoundError("no or too many packages specified."))))
    } else {
      val pkgInfo = config.packageInfoInputMap.keys.head
      val preprocessFuture = verifier.verify(pkgInfo, config)(executor)
      serverExceptionHandling(fileData, isolate, None, preprocessFuture)
    }
  }

  /**
    * Verify Viper AST. Assumes that the config only holds a single package to be verified, otherwise returns a
    * verification failure.
    */
  def verifyAst(verifierConfig: VerifierConfig, ast: Program, backtrack: BackTrackInfo, startTime: Long, completedProgress: Int)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    val fileModeConfig = Helper.getFileModeConfig(_server, verifierConfig, startTime, stopAfterEncoding = false, completedProgress = completedProgress, ast = Some(ast))(executor)
    fileModeConfig.config match {
      case Right(config) =>
        if (config.packageInfoInputMap.keys.size != 1) {
          successful(VerifierResult.Failure(Vector(NotFoundError("no or too many packages specified."))))
        } else {
          val pkgInfo = config.packageInfoInputMap.keys.head
          val resultFuture = verifier.verifyAst(config, pkgInfo, ast, backtrack)(executor)
          serverExceptionHandling(verifierConfig.fileData, verifierConfig.isolate, Some(ast), resultFuture)
        }
      case Left(errMsg) => successful(VerifierResult.Failure(Vector(ConfigError(errMsg))))
    }
  }

  /**
    * Goify File and publish potential errors as Diagnostics.
    */
  def goify(fileData: FileData)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    val fileUri = fileData.fileUri
    val config = Helper.goifyConfigFromTask(fileData)
    val goifyFuture = verify(Array(fileData), Array.empty, config)(executor)

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

    val filePath = Helper.uri2Path(fileData.fileUri).toString

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
  def codePreview(fileData: Array[FileData], internalPreview: Boolean, viperPreview: Boolean, selections: List[Range])(executor: GobraExecutionContext): Future[VerifierResult] = {
    val config = Helper.previewConfigFromTask(fileData.toVector, internalPreview, viperPreview, selections)
    verify(fileData, Array.empty, config)(executor)
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
    _server = null
  } 
}
