package viper.gobraserver

import scala.concurrent.ExecutionContext

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class VerificationWorker extends Runnable {
  implicit val exectuionContext = ExecutionContext.global

  private var verificationJob: VerifierConfig = null
  private var fileType: FileType.Value = null

  def run() {
    try {
      while(true) {
        VerifierState.jobQueue.synchronized {
          if (VerifierState.jobQueue.isEmpty) {
            VerifierState.jobQueue.wait()
          }
          val newJob = VerifierState.jobQueue.dequeue()
          verificationJob = newJob._2
          fileType = newJob._1
        }

        if (fileType == FileType.Gobra) {
          val resultFuture = GobraServer.verify(verificationJob)
          Await.result(resultFuture, Duration.Inf)
        } else if (fileType == FileType.Go) {
          val resultFuture = GobraServer.verifyGo(verificationJob)
          Await.result(resultFuture, Duration.Inf)
        }

      }
    } catch {
      case e: InterruptedException => println("VerificationWorker got interrupted.")
      case _: Throwable => run() // restart the verification worker
    }

  }
}