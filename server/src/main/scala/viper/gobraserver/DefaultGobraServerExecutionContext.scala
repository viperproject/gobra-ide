package viper.gobraserver

import java.util.concurrent.ExecutorService

import viper.server.core.DefaultVerificationExecutionContext

class DefaultGobraServerExecutionContext(val threadPoolSize: Int = DefaultVerificationExecutionContext.minNumberOfThreads) extends DefaultVerificationExecutionContext with GobraServerExecutionContext {
  override lazy val nThreads: Int = threadPoolSize
  lazy val service: ExecutorService = executorService
}
