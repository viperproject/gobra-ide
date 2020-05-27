package viper.gobraserver

import scala.collection.mutable.Queue
import scala.concurrent.{ Future, ExecutionContext }

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class VerificationWorker extends Runnable {
  implicit val exectuionContext = ExecutionContext.global

  private var verificationJob: VerifierConfig = null

  def run() {
    try {
      while(true) {
        VerifierState.jobQueue.synchronized {
          if (VerifierState.jobQueue.isEmpty) {
            VerifierState.jobQueue.wait()
          }
          verificationJob = VerifierState.jobQueue.dequeue()
        }
        val resultFuture = GobraServer.verify(verificationJob)
        Await.result(resultFuture, Duration.Inf)

      }
    } catch {
      case e: InterruptedException => println("VerificationWorker got interrupted.")
      case _ => run() // restart the verification worker
    }

  }
}