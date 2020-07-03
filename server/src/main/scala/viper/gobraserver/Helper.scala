package viper.gobraserver

import viper.gobra.frontend.Config
import viper.gobra.backend.ViperBackends
import viper.server.ViperBackendConfigs
import viper.gobra.reporting.{ FileWriterReporter, VerifierResult }

import org.eclipse.lsp4j.Range

import java.io.File
import java.nio.file.{ Files, Paths }

import ch.qos.logback.classic.Level

object Helper {

  def verificationConfigFromTask(verifierConfig: VerifierConfig, startTime: Long): Config = {
    verifierConfig match {
      case VerifierConfig(
        FileData(path, fileUri),
        GobraSettings(backendId, serverMode, debug, eraseGhost, goify, unparse, printInternal, printViper, parseOnly, logLevel),
        z3Exe,
        boogieExe
      ) => {

        val shouldParse = true
        val shouldTypeCheck = !parseOnly
        val shouldDesugar = shouldTypeCheck
        val shouldViperEncode = shouldDesugar
        val shouldVerify = shouldViperEncode

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

        val reporter = GobraIdeReporter(
          startTime = startTime,
          fileUri = fileUri,
          backend = backend,
          unparse = unparse,
          eraseGhost = eraseGhost,
          goify = goify,
          debug = debug,
          printInternal = printInternal,
          printVpr = printViper
        )

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

    Config(
      inputFile = new File(fileData.filePath),
      shouldDesugar = false,
      shouldViperEncode = false,
      shouldVerify = false,
      reporter = reporter
    )
  }

  def previewConfigFromTask(fileData: FileData, viperPreview: Boolean, selections: List[Range]): Config = {
    val reporter = PreviewReporter(
      viperPreview = viperPreview,
      selections = selections
    )

    Config(
      inputFile = new File(fileData.filePath),
      shouldVerify = false,
      reporter = reporter
    )
  }


  def getOverallVerificationResult(fileUri: String, result: VerifierResult, elapsedTime: Long): OverallVerificationResult = {
    result match {
      case VerifierResult.Success =>
        OverallVerificationResult(
          fileUri = fileUri,
          success = true,
          message = "Verification succeeded in " + (elapsedTime/1000) + "." + (elapsedTime%1000)/10 + "s"
        )
      case VerifierResult.Failure(errors) =>
        OverallVerificationResult(
          fileUri = fileUri,
          success = false,
          message = "Verification failed in " + (elapsedTime / 1000) + "." + (elapsedTime%1000)/10 + "s with: " + errors.head.id
        )
    }
  }

  def getOverallVerificationResultFromException(fileUri: String, e: Throwable): OverallVerificationResult = {
    OverallVerificationResult(
      fileUri = fileUri,
      success = false,
      message = e.getMessage()
    )
  }


  def startLine(range: Range): Int = range.getStart().getLine()
  def startChar(range: Range): Int = range.getStart().getCharacter()
  def endLine(range: Range): Int = range.getEnd().getLine()
  def endChar(range: Range): Int = range.getEnd().getCharacter()

  def gobraFileExtension(uri: String): String = {
    val dropSuffix = if (uri.endsWith(".go")) uri.dropRight(3) else uri
    if (dropSuffix.endsWith(".gobra")) dropSuffix else dropSuffix + ".gobra"
  }

  def indentBlock(code: String, block: String): String = {
    var tmp = block
    while (!code.contains(tmp)) tmp = " " + tmp.replaceAll("\n", "\n ")
    return tmp
  }
}