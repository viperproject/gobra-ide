// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver.backend

import viper.silver.ast.Program
import viper.silver.reporter.Reporter
import viper.silver.verifier.VerificationResult
import viper.gobra.backend.{ViperVerifier, ViperVerifierConfig}

import scala.concurrent.Future
import viper.gobra.util.GobraExecutionContext
import viper.gobraserver.GobraServerExecutionContext
import viper.server.core.{CarbonConfig, SiliconConfig, ViperBackendConfig, ViperCoreServer, ViperCoreServerUtils, ViperServerBackendNotFoundException}


object ViperServerConfig {
  object EmptyConfigWithSilicon extends ViperServerWithSilicon {val partialCommandLine: List[String] = Nil}
  object EmptyConfigWithCarbon extends ViperServerWithCarbon {val partialCommandLine: List[String] = Nil}
  case class ConfigWithSilicon(partialCommandLine: List[String]) extends ViperServerWithSilicon
  case class ConfigWithCarbon(partialCommandLine: List[String]) extends ViperServerWithCarbon
}
trait ViperServerWithSilicon extends ViperVerifierConfig
trait ViperServerWithCarbon extends ViperVerifierConfig

class ViperServer(server: ViperCoreServer)(executor: GobraServerExecutionContext) extends ViperVerifier {

  override def verify(programID: String, config: ViperVerifierConfig, reporter: Reporter, program: Program)(_ctx: GobraExecutionContext): Future[VerificationResult] = {
    // convert ViperVerifierConfig to ViperBackendConfig:
    val serverConfig: ViperBackendConfig = config match {
      case _: ViperServerWithSilicon => SiliconConfig(config.partialCommandLine)
      case _: ViperServerWithCarbon => CarbonConfig(config.partialCommandLine)
      case c => throw ViperServerBackendNotFoundException(s"unknown backend config $c")
    }
    val handle = server.verify(programID, serverConfig, program)
    ViperCoreServerUtils.getResultsFuture(server, handle)(executor)
  }
}
