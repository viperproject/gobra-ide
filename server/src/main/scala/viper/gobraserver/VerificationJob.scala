// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.gobraserver

import com.google.gson.Gson
import viper.gobra.VerificationHandle

import scala.concurrent.{Future, Promise}

object VerificationJob {
  private val gson: Gson = new Gson()
}

/**
  * State of a single verification unit (= one `gobraServer/verify` request). The state machine
  * transitions Running -> Finished or Running -> Aborted exactly once (guarded by `synchronized`),
  * which guarantees exactly-once bookkeeping no matter how stopping races with completion.
  */
final class VerificationJob(val fileUris: Vector[String]) {
  private var running = true
  private var aborted = false
  /** handle to the Gobra verification; set right after starting the verification */
  @volatile var handle: Option[VerificationHandle] = None
  private val promise: Promise[String] = Promise()

  def isAborted: Boolean = this.synchronized { aborted }

  /** Running -> Finished; returns true iff this call performed the transition */
  def tryFinish(): Boolean = this.synchronized {
    if (running) { running = false; true } else false
  }

  /** Running -> Aborted; returns true iff this call performed the transition */
  def tryAbort(): Boolean = this.synchronized {
    if (running) { running = false; aborted = true; true } else false
  }

  /** settles the response of the corresponding `gobraServer/verify` request (idempotent) */
  def completeWith(result: OverallVerificationResult): Unit = promise.trySuccess(VerificationJob.gson.toJson(result))
  def failWith(e: Throwable): Unit = promise.tryFailure(e)
  def resultFuture: Future[String] = promise.future
}
