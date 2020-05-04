package viper.gobraserver

import viper.gobra.frontend.Config
import viper.gobra.backend.ViperBackends
import viper.server.ViperBackendConfigs
import viper.gobra.reporting.FileWriterReporter

import java.io.File

import ch.qos.logback.classic.Level

object Helper {
  def configFromTask(verifierConfig: VerifierConfig): Config = {
    verifierConfig match {
      case VerifierConfig(
        FileData(path, _),
        ClientConfig(backendId, serverMode, debug, eraseGhost, unparse, printInternal, printViper, parseOnly, logLevel)
      ) => {

        val shouldParse = true
        val shouldTypeCheck = !parseOnly
        val shouldDesugar = shouldTypeCheck
        val shouldViperEncode = shouldDesugar
        val shouldVerify = shouldViperEncode

        val reporter = FileWriterReporter(
          unparse = unparse,
          eraseGhost = eraseGhost,
          debug = debug,
          printInternal = printInternal,
          printVpr = printViper
        )

        val backend =
          if (serverMode)
            ViperBackends.ViperServerBackend
          else
            backendId match {
              case "SILICON" => ViperBackends.SiliconBackend
              case "CARBON" => ViperBackends.CarbonBackend
              case _ => ViperBackends.SiliconBackend
            }

        val backendConfig = 
          if (serverMode)
            backendId match {
              case "SILICON" =>
                var options: List[String] = List()
                options ++= List("--logLevel", "ERROR")
                options ++= List("--disableCatchingExceptions")
                options ++= List("--enableMoreCompleteExhale")

                ViperBackendConfigs.SiliconConfig(options)
              
              case "CARBON" =>
                var options: List[String] = List()
                
                ViperBackendConfigs.CarbonConfig(options)

              case _ => ViperBackendConfigs.EmptyConfig
            }
          else
            ViperBackendConfigs.EmptyConfig


        Config(
          inputFile = new File(path),
          reporter = FileWriterReporter(
            unparse = unparse,
            eraseGhost = eraseGhost,
            debug = debug,
            printInternal = printInternal,
            printVpr = printViper),
          backend = backend,
          backendConfig = backendConfig,
          logLevel = Level.toLevel(logLevel),
          shouldParse = shouldParse,
          shouldTypeCheck = shouldTypeCheck,
          shouldDesugar = shouldDesugar,
          shouldViperEncode = shouldViperEncode,
          shouldVerify = shouldVerify
        )
      }
    }
  }
}