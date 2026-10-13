// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import viper.gobra.Gobra
import viper.gobra.GobraFrontend
import viper.gobra.reporting.{NotFoundError, VerifierError, VerifierResult}
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.{ResponseError, ResponseErrorCode}

import java.util.concurrent.CancellationException
import viper.gobra.util.{GobraExecutionContext, Violation}
import viper.gobra.reporting.BackTranslator.BackTrackInfo
import viper.silver.ast.Program
import viper.server.core.ViperCoreServer
import org.eclipse.lsp4j.Range
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
    * Clients are not notified that verification is done if `reporter` has submitted the verification of the
    * generated Viper AST as a separate job because that job reports the overall result instead.
    */
  private def serverExceptionHandling(verifierConfig: VerifierConfig, reporter: VerificationFinishNotifier, ast: Option[Program], job: VerificationJob, resultFuture: Future[VerifierResult])(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    val fileUris = verifierConfig.fileData.map(_.fileUri)
    val isolate = verifierConfig.isolate

    // do some post processing if verification has failed
    resultFuture.transformWith {
      case Success(VerifierResult.Aborted) =>
        // the verification has been aborted (Gobra reports this as a regular result). All
        // bookkeeping has already happened when the verification was stopped -- just make sure
        // that the request's response is settled:
        _server.globalLogger.info(s"GobraServer: the verification of $fileUris has been aborted")
        job.failWith(new CancellationException("the verification has been stopped"))
        Future.successful(VerifierResult.Aborted)
      case Success(res) =>
        _server.globalLogger.info(s"GobraServer: Gobra handled request successfully: $res")
        // note that we cannot derive from `res` whether a separate job has been submitted: Gobra reports
        // `VerifierResult.Skipped` whenever the config disables a step of its pipeline, which is the case
        // both when we stop after the encoding (a job has been submitted) and when, e.g., the `parseOnly`
        // setting is enabled (no Viper AST has been generated, i.e., no job has been submitted):
        if (!reporter.hasSubmittedAstJob) {
          reporter.notifyOverallVerificationFinished(res, ast)
        }
        Future.successful(res)
      case Failure(exception) =>
        // note that stopping a verification does not cause a failure: Gobra reports an aborted
        // verification as a regular result (handled above). Hence, a failure is unexpected even if
        // the verification has been stopped in the meantime.
        // restart Gobra Server and then update client state
        // ignore result of restart and inform the client:
        val finishedNow = job.tryFinish() // false if the reporter has already reported a result, or if the verification has been stopped
        restart().transformWith(_ => {
          if (finishedNow) {
            // note that `verificationRunning` was previously never decremented on this path:
            VerifierState.verificationRunning = math.max(0, VerifierState.verificationRunning - 1)
            VerifierState.changes = VerifierState.changes.filter(change => !fileUris.contains(change._1))
          }
          exception match {
            case e: Violation.LogicException =>
              fileUris.foreach(VerifierState.removeDiagnostics)
              val overallResult = Helper.getOverallVerificationResultFromException(fileUris, isolate, ast, e)

              VerifierState.updateVerificationInformation(fileUris.toVector, Right(overallResult))

              fileUris.foreach(fileUri => VerifierState.publishDiagnostics(fileUri, Some(_server.globalLogger)))
              job.completeWith(overallResult)

            case e =>
              println("Exception occurred:")
              e.printStackTrace()

              // remove verification information about this file
              // otherwise, reopening this file in the client will result in sending the last progress although no
              // verification is running
              VerifierState.removeVerificationInformation(fileUris.toVector)

              // the client displays the request's error response to the user:
              job.failWith(new ResponseErrorException(new ResponseError(ResponseErrorCode.InternalError,
                s"An exception occurred during execution of Gobra: $e", null)))
          }
          // forward original result
          Future.failed(exception)
        })
    }
  }

  /**
    * Stops the given verification: performs the client-state bookkeeping exactly once and cancels
    * the Gobra verification, which aborts Gobra's compilation stages at the next stage boundary
    * and interrupts a running backend verification. Stopping an already finished verification is
    * a no-op.
    */
  def stopVerification(job: VerificationJob): Unit = {
    if (job.tryAbort()) {
      _server.globalLogger.info(s"GobraServer: stopping the verification of ${job.fileUris.mkString(", ")}")
      VerifierState.verificationRunning = math.max(0, VerifierState.verificationRunning - 1)
      VerifierState.changes = VerifierState.changes.filter(change => !job.fileUris.contains(change._1))
      VerifierState.removeVerificationInformation(job.fileUris)
      // note that `handle` is guaranteed to be cancelled even if it is only created after this
      // point: `preprocess` cancels a freshly created handle of an already aborted job:
      job.handle.foreach(_.cancel())
      job.failWith(new CancellationException("the verification has been stopped"))
    }
  }

  /**
    * Preprocess file and enqueue the Viper AST whenever it is created.
    */
  def preprocess(verifierConfig: VerifierConfig)(implicit executor: GobraExecutionContext): VerificationJob = {
    val fileUris = verifierConfig.fileData.map(_.fileUri)
    val job = new VerificationJob(fileUris.toVector)

    VerifierState.verificationRunning += 1
    fileUris.foreach(VerifierState.removeDiagnostics)

    val startTime = System.currentTimeMillis()

    val reporter = Helper.getReporter(verifierConfig, _server, startTime = startTime, stopAfterEncoding = !VerificationInOneStep, job = job)(executor)
    val fileModeConfig = Helper.getFileModeConfig(verifierConfig, _server, reporter, stopAfterEncoding = !VerificationInOneStep)
    val fut = fileModeConfig.config match {
      case Right(config) if config.packageInfoInputMap.keys.size == 1 =>
        val pkgInfo = config.packageInfoInputMap.keys.head
        val handle = verifier.verifyCancellable(pkgInfo, config)(executor)
        job.handle = Some(handle)
        // the verification might have been stopped before the handle was created -- cancel right away:
        if (job.isAborted) {
          handle.cancel()
        }
        handle.result
      case Right(_) => successful(VerifierResult.Failure(Vector(NotFoundError("no or too many packages specified."))))
      case Left(errs) => successful(VerifierResult.Failure(errs))
    }
    serverExceptionHandling(verifierConfig, reporter, None, job, fut)
    job
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
    // note that this two-step verification path does not support stopping -- the job only serves the bookkeeping:
    val job = new VerificationJob(verifierConfig.fileData.map(_.fileUri).toVector)
    val reporter = Helper.getReporter(verifierConfig, _server, startTime = startTime, stopAfterEncoding = false, job = job, completedProgress = completedProgress, ast = Some(ast))(executor)
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
    serverExceptionHandling(verifierConfig, reporter, Some(ast), job, fut)
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
      // the config disables all steps after type-checking, i.e., `Skipped` (and not `Success`) is the
      // result that Gobra reports for a successful goification, see `Helper.goifyConfigFromTask`:
      case (Success(VerifierResult.Skipped), Some(c)) =>
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

    if (newFileUri == VerifierState.openFileUri) VerifierState.publishDiagnostics(newFileUri, Some(_server.globalLogger))

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
    * The preview is sent to the client by the reporter. The returned result merely indicates whether
    * producing the preview was successful, in which case it is `VerifierResult.Skipped` (and not
    * `Success`) because the config disables the verification, see `Helper.previewConfigFromTask`.
    */
  def codePreview(fileData: Array[FileData], internalPreview: Boolean, viperPreview: Boolean, selections: List[Range])(implicit executor: GobraExecutionContext): Future[VerifierResult] = {
    val eitherResult = for {
      config <- EitherT.fromEither(Future.successful[Either[Vector[VerifierError], Config]](Helper.previewConfigFromTask(fileData.toVector, internalPreview, viperPreview, selections)))
      result <- EitherT.rightT(verify(config)(executor))
    } yield result
    eitherResult.fold(errs => VerifierResult.Failure(errs), identity)
  }


  def stop(): Future[Unit] = {
    // the server might have never been initialized, e.g., because it rejected the initialize
    // request of an incompatible client. In this case, there is nothing to stop. Note that this
    // case is not just hypothetical: incompatible clients stop the server, e.g., when updating
    // the Gobra tools, and responding with an error would abort such an update:
    if (_server == null) {
      return Future.successful(())
    }
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
