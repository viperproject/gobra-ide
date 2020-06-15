package viper.gobraserver

import viper.gobra.frontend.Config
import viper.gobra.backend.ViperBackends
import viper.server.ViperBackendConfigs
import viper.gobra.reporting.{ FileWriterReporter, VerifierResult, NoopReporter }

import org.eclipse.lsp4j.Range

import java.io.File
import java.nio.file.{ Files, Paths }

import ch.qos.logback.classic.Level

object Helper {

  def verificationConfigFromTask(verifierConfig: VerifierConfig): Config = {
    verifierConfig match {
      case VerifierConfig(
        FileData(path, fileUri),
        GobraSettings(backendId, serverMode, z3Exe, boogieExe, debug, eraseGhost, goify, unparse, printInternal, printViper, parseOnly, logLevel)
      ) => {

        val shouldParse = true
        val shouldTypeCheck = !parseOnly
        val shouldDesugar = shouldTypeCheck
        val shouldViperEncode = shouldDesugar
        val shouldVerify = shouldViperEncode

        val reporter = FileWriterReporter(
          unparse = unparse,
          eraseGhost = eraseGhost,
          goify = goify,
          debug = debug,
          printInternal = printInternal,
          printVpr = printViper
        )

        val backend =
          if (serverMode) {
            ViperBackends.ViperServerBackend
          } else {
            backendId match {
              case "SILICON" => ViperBackends.SiliconBackend
              case "CARBON" => ViperBackends.CarbonBackend
              case _ => ViperBackends.SiliconBackend
            }
          }

        val backendConfig = 
          if (serverMode) {
            var options: Vector[String] = Vector.empty

            if (z3Exe != null && Files.exists(Paths.get(z3Exe)))
              options ++= Vector("--z3Exe", z3Exe)

            backendId match {
              case "SILICON" =>
                //var options: List[String] = List()
                options ++= Vector("--logLevel", "ERROR")
                options ++= Vector("--disableCatchingExceptions")
                options ++= Vector("--enableMoreCompleteExhale")

                ViperBackendConfigs.SiliconConfig(options.toList)
              
              case "CARBON" =>
                //var options: List[String] = List()
                if (boogieExe != null && Files.exists(Paths.get(boogieExe)))
                  options ++= Vector("--boogieExe", boogieExe)

                ViperBackendConfigs.CarbonConfig(options.toList)

              case _ => ViperBackendConfigs.EmptyConfig
            }
          } else
            ViperBackendConfigs.EmptyConfig


        Config(
          inputFile = new File(path),
          reporter = reporter,
          backend = backend,
          backendConfig = backendConfig,
          z3Exe = z3Exe,
          boogieExe = boogieExe,
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

  def goifyConfigFromTask(fileData: FileData): Config = {
    val reporter = FileWriterReporter(goify = true)

    Config(inputFile = new File(fileData.filePath), reporter = reporter)
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

  def getOverallVerificationResultFromException(e: Throwable): OverallVerificationResult = {
    OverallVerificationResult(
      success = false,
      message = e.getMessage()
    )
  }

  def getOverallVerificationResult(e: Throwable): OverallVerificationResult = {
    OverallVerificationResult(
      success = false,
      message = "An error occured during verification: " + e
    )
  }

  def sendFinishedVerification(fileUri: String) {
    VerifierState.client match {
      case Some(c) => c.finishedVerification(fileUri)
      case None =>
    }
  }

  def startLine(range: Range): Int = range.getStart().getLine()
  def startChar(range: Range): Int = range.getStart().getCharacter()
  def endLine(range: Range): Int = range.getEnd().getLine()
  def endChar(range: Range): Int = range.getEnd().getCharacter()
}