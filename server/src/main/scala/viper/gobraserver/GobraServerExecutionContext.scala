package viper.gobraserver

import java.util.concurrent.ExecutorService

import viper.gobra.util.GobraExecutionContext
import viper.server.core.VerificationExecutionContext

trait GobraServerExecutionContext extends VerificationExecutionContext with GobraExecutionContext {
  def service: ExecutorService
}
