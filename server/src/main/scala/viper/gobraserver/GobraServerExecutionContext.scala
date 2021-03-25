// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import java.util.concurrent.ExecutorService

import viper.gobra.util.GobraExecutionContext
import viper.server.core.{DefaultVerificationExecutionContext, VerificationExecutionContext}

trait GobraServerExecutionContext extends VerificationExecutionContext with GobraExecutionContext {
  def service: ExecutorService
}

class DefaultGobraServerExecutionContext(val threadPoolSize: Int = DefaultVerificationExecutionContext.minNumberOfThreads) extends DefaultVerificationExecutionContext with GobraServerExecutionContext {
  override lazy val nThreads: Int = threadPoolSize
  lazy val service: ExecutorService = executorService
}
