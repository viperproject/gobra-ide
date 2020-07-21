package viper.gobraserver

import scala.concurrent.ExecutionContext

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import viper.silver.ast.Program
import viper.gobra.reporting.BackTranslator.BackTrackInfo

class VerificationWorker extends Runnable {
  implicit val exectuionContext = ExecutionContext.global

  private var verificationJob: (() => Program, () => BackTrackInfo, Long, VerifierConfig) = _

  def run() {
    try {
      while(true) {
        VerifierState.jobQueue.synchronized {
          if (VerifierState.jobQueue.isEmpty) VerifierState.jobQueue.wait()

          verificationJob = VerifierState.jobQueue.dequeue()
        }

        val (ast, backtrack, startTime, verifierConfig) = verificationJob

        val resultFuture = GobraServer.verify(verifierConfig, ast, backtrack, startTime)
        Await.result(resultFuture, Duration.Inf)
      }
    } catch {
      case e: InterruptedException => println("VerificationWorker got interrupted.")
      case _: Throwable => run() // restart the verification worker
    }

  }
}