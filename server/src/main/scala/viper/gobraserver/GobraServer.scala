// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import com.google.gson.Gson
import viper.gobra.Gobra
import viper.gobra.GobraFrontend
import viper.gobra.reporting.{NotFoundError, VerifierError, VerifierResult}
import viper.gobra.util.{GobraExecutionContext, Violation}
import viper.gobra.reporting.BackTranslator.BackTrackInfo
import viper.silver.ast.Program
import viper.server.core.ViperCoreServer
import org.eclipse.lsp4j.{MessageParams, MessageType, Range}
import scalaz.EitherT
import scalaz.Scalaz.futureInstance
import viper.gobra.frontend.{Config, Gobrafier, Parser}
import viper.server.ViperConfig
import viper.server.vsi.DefaultVerificationServerStart

import java.io.{BufferedWriter, File, FileWriter}
import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.io.Source
import scala.util.{Failure, Success}


object GobraServer extends GobraFrontend {
  /**
    * constant controlling whether verification of a file should be done as one big task (using Gobra's usual
    * verification flow) or in two parts (Gobra produces the Viper AST in part 1, which is then externally passed
    * to ViperServer for verification as part 2)
    */
  private val VerificationInOneStep: Boolean = true

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

  /** handles completion of `resultFuture` especially if the future fails (as opposed to a verification failure).
    * `ignoreVerificationSuccess` instructs this handler to ignore verification success by not notifying clients
    * that verification is done, which is especially useful if additional work is necessary after this future's
    * completion.
    */
  private def serverExceptionHandling(verifierConfig: VerifierConfig, reporter: VerificationFinishNotifier, ast: Option[Program], resultFuture: Future[VerifierResult], ignoreVerificationSuccess: Boolean = false)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    val fileUris = verifierConfig.fileData.map(_.fileUri)
    val isolate = verifierConfig.isolate

    // do some post processing if verification has failed
    resultFuture.transformWith {
      case Success(res) =>
        _server.globalLogger.info(s"GobraServer: Gobra handled request successfully: $res")
        if (!ignoreVerificationSuccess || res != VerifierResult.Success) {
          reporter.notifyOverallVerificationFinished(res, ast)
        }
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

    val reporter = Helper.getReporter(verifierConfig, _server, startTime = startTime, stopAfterEncoding = !VerificationInOneStep)(executor)
    val fileModeConfig = Helper.getFileModeConfig(verifierConfig, _server, reporter, stopAfterEncoding = !VerificationInOneStep)
    val fut = fileModeConfig.config match {
      case Right(config) => verify(config)
      case Left(errs) => successful(VerifierResult.Failure(errs))
    }
    serverExceptionHandling(verifierConfig, reporter, None, fut, ignoreVerificationSuccess = !VerificationInOneStep)
  }

  /**
    * Wrapper around invoking Gobra with a particular config. Assumes that the config only holds a single package to be
    * verified, otherwise returns a verification failure.
    */
  def verify(config: Config)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    if (config.packageInfoInputMap.keys.size != 1) {
      successful(VerifierResult.Failure(Vector(NotFoundError("no or too many packages specified."))))
    } else {
      val pkgInfo = config.packageInfoInputMap.keys.head
      verifier.verify(pkgInfo, config)(executor)
    }
  }

  /**
    * Verify Viper AST. Assumes that the config only holds a single package to be verified, otherwise returns a
    * verification failure.
    */
  def verifyAst(verifierConfig: VerifierConfig, ast: Program, backtrack: BackTrackInfo, startTime: Long, completedProgress: Int)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    require(!VerificationInOneStep)
    val reporter = Helper.getReporter(verifierConfig, _server, startTime = startTime, stopAfterEncoding = false, completedProgress = completedProgress, ast = Some(ast))(executor)
    val fileModeConfig = Helper.getFileModeConfig(verifierConfig, _server, reporter, stopAfterEncoding = false)
    val fut = fileModeConfig.config match {
      case Right(config) =>
        if (config.packageInfoInputMap.keys.size != 1) {
          successful(VerifierResult.Failure(Vector(NotFoundError("no or too many packages specified."))))
        } else {
          val pkgInfo = config.packageInfoInputMap.keys.head
          verifier.verifyAst(config, pkgInfo, ast, backtrack)(executor)
        }
      case Left(errs) => successful(VerifierResult.Failure(errs))
    }
    serverExceptionHandling(verifierConfig, reporter, Some(ast), fut)
  }

  /**
    * Goify File and publish potential errors as Diagnostics.
    */
  def goify(fileData: FileData)(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    val fileUri = fileData.fileUri

    val eitherResult = for {
      config <- EitherT.fromEither(Future.successful[Either[Vector[VerifierError], Config]](Helper.goifyConfigFromTask(fileData)))
      result <- EitherT.rightT(verify(config)(executor))
    } yield result

    val resultFut = eitherResult.fold(errs => VerifierResult.Failure(errs), identity)
    resultFut.onComplete(res => (res, VerifierState.client) match {
      case (Success(VerifierResult.Success), Some(c)) =>
        c.finishedGoifying(fileUri, success = true)
      case (_, Some(c)) =>
        c.finishedGoifying(fileUri, success = false)
      case _ => // no client to inform
    })

    resultFut
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
  def codePreview(fileData: Array[FileData], internalPreview: Boolean, viperPreview: Boolean, selections: List[Range])(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    val eitherResult = for {
      config <- EitherT.fromEither(Future.successful[Either[Vector[VerifierError], Config]](Helper.previewConfigFromTask(fileData.toVector, internalPreview, viperPreview, selections)))
      result <- EitherT.rightT(verify(config)(executor))
    } yield result
    eitherResult.fold(errs => VerifierResult.Failure(errs), identity)
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
