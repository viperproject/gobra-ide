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
