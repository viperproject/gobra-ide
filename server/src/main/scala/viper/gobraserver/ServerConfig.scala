// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import ch.qos.logback.classic.Level
import org.rogach.scallop.{ScallopConf, ScallopOption, intConverter, numberHandler, singleArgConverter}
import viper.gobra.frontend.LoggerDefaults
import viper.server.core.DefaultVerificationExecutionContext

class ServerConfig(arguments: Seq[String])
  extends ScallopConf(arguments) {

  /**
    * Prologue
    */

  version(
    s"""
       | ${Server.name} ${Server.copyright}
       |   version ${Server.version}
     """.stripMargin
  )

  banner(
    s""" Usage: ${Server.name} [OPTIONS]
       |
       | Options:
       |""".stripMargin
  )

  /**
    * Command-line options
    */
  private val portOpt: ScallopOption[Int] = opt[Int](
    name = "port",
    descr = s"Port on which ${Server.name} should listen. Port '0' will result in choosing an arbitrary available port.",
    default = Some(0)
  )

  /**
    * minimum number of threads that Gobra Server needs; it's one more than the minimum number of threads for the
    * default verification execution context as 1 thread will be used to by LSP4j to process the sockets
    */
  val minNumberOfThreads: Int = DefaultVerificationExecutionContext.minNumberOfThreads + 1
  private val nThreadsOpt: ScallopOption[Int] = opt[Int](
    name = "nThreads",
    descr = s"Maximal number of threads that should be used (not taking threads used by backend into account)\n" +
      s"Values below $minNumberOfThreads (the minimum) will be set to the minimum.\n" +
      s"The default value is the maximum of $minNumberOfThreads and the number of available processors",
    default = Some(Math.max(minNumberOfThreads, Runtime.getRuntime.availableProcessors())),
    noshort = true
  )(singleArgConverter(input => {
    // parse option as int and check bounds
    val n = input.toInt
    n match {
      case n if n < minNumberOfThreads => minNumberOfThreads
      case n => n
    }
  }, numberHandler("Int")))

  val logLevelOpt: ScallopOption[Level] = opt[Level](
    name = "logLevel",
    descr =
      "One of the log levels ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF (default: OFF)",
    default = Some(LoggerDefaults.DefaultLevel),
    noshort = true
  )(singleArgConverter(arg => Level.toLevel(arg.toUpperCase)))

  /**
    * Exception handling
    */
  /**
    * Epilogue
    */

  verify()

  val port: Int = portOpt()
  val nThreads: Int = nThreadsOpt()
  val logLevel: Level = logLevelOpt()
}
