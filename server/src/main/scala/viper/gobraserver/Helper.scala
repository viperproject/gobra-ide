package viper.gobraserver

import viper.gobra.frontend.Config
import viper.gobra.backend.ViperBackends
import viper.server.ViperBackendConfigs
import viper.gobra.reporting.{ FileWriterReporter, VerifierResult, NoopReporter }

import java.io.File

import ch.qos.logback.classic.Level

object Helper {
  def configFromTask(verifierConfig: VerifierConfig): Config = {
    verifierConfig match {
      case VerifierConfig(
        FileData(path, fileUri),
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
          if (serverMode) {
            ViperBackends.ViperServerBackend
          } else {
            VerifierState.resetFileChanges(fileUri)
            backendId match {
              case "SILICON" => ViperBackends.SiliconBackend
              case "CARBON" => ViperBackends.CarbonBackend
              case _ => ViperBackends.SiliconBackend
            }
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


  def getOverallVerificationResult(result: VerifierResult, elapsedTime: Long): OverallVerificationResult = {
    result match {
      case VerifierResult.Success =>
        OverallVerificationResult(
          success = true,
          message = "Verification succeeded in " + (elapsedTime/1000) + "." + (elapsedTime%1000)/10 + "s"
        )
      case VerifierResult.Failure(errors) =>
        OverallVerificationResult(
          success = false,
          message = "Verification failed in " + (elapsedTime / 1000) + "." + (elapsedTime%1000)/10 + "s with: " + errors.head.id
        )
    }
  }

  def getOverallVerificationResult(e: Throwable): OverallVerificationResult = {
    OverallVerificationResult(
      success = false,
      message = "An error occured during verification: " + e
    )
  }
}