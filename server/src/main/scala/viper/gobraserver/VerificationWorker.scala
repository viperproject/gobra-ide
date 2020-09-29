// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration.Duration
import viper.silver.ast.Program
import viper.gobra.reporting.BackTranslator.BackTrackInfo

class VerificationWorker extends Runnable {
  implicit val exectuionContext: ExecutionContextExecutor = ExecutionContext.global

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