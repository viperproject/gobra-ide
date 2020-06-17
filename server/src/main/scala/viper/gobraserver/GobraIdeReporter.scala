package viper.gobraserver

import viper.gobra.reporting._
import viper.silver.reporter.StatisticsReport
import viper.gobra.util.OutputUtil

import org.apache.commons.io.FileUtils
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8

case class GobraIdeReporter(name: String = "gobraide_reporter",
                            fileUri: String,
                            verificationFraction: Double = 0.75,
                            unparse: Boolean = false,
                            eraseGhost: Boolean = false,
                            goify: Boolean = false,
                            debug: Boolean = false,
                            printInternal: Boolean = false,
                            printVpr: Boolean = false) extends GobraReporter {

  // amount of process a non verification message contributes to the progress
  private def nonVerificationEntityProgress: Int = ((1 - verificationFraction) * 20).round.toInt

  private var progress: Int = 0
  private val finishedProgress: Int = 100
  private var totalEntities: Int = 0

  private def verificationEntityProgress: Int =
    (100 * verificationFraction).round.toInt * (if (totalEntities == 0) 1 else (1 / totalEntities))

  
  private def updateProgress(update: Int): Unit = {
    progress += update
    VerifierState.updateVerificationInformation(fileUri, Left(progress))
  }

  override def report(msg: GobraMessage): Unit = msg match {
    case CopyrightReport(text) => println(text)

    case PreprocessedInputMessage(_, _) => updateProgress(nonVerificationEntityProgress)

    case ParsedInputMessage(file, program) =>
      updateProgress(nonVerificationEntityProgress)
      if (unparse) write(file, "unparsed", program().formatted)

    case TypeCheckSuccessMessage(file, _, erasedGhostCode) =>
      updateProgress(nonVerificationEntityProgress)
      if (eraseGhost) write(file, "ghostLess", erasedGhostCode())
      if (goify) write(file, "go", erasedGhostCode())

    case DesugaredMessage(file, internal) =>
      updateProgress(nonVerificationEntityProgress)
      if (printInternal) write(file, "internal", internal().formatted)

    case m@GeneratedViperMessage(file, _) =>
      updateProgress(nonVerificationEntityProgress)
      if (printVpr) write(file, "vpr", m.vprAstFormatted)

    
    case GobraOverallSuccessMessage(_) => updateProgress(finishedProgress)

    case GobraOverallFailureMessage(_, _) => updateProgress(finishedProgress)

    case GobraEntitySuccessMessage(_, _) => updateProgress(verificationEntityProgress)

    case GobraEntityFailureMessage(_, _, _) => updateProgress(verificationEntityProgress)

    case RawMessage(m) => m match {
      case StatisticsReport(nOfMethods, nOfFunctions, nOfPredicates, nOfDomains, nOfFields) =>
        totalEntities = nOfMethods + nOfFunctions + nOfPredicates
      case _ => // ignore
    }

    case _ => // ignore
  }

  private def write(file: File, fileExt: String, content: String): Unit = {
    val outputFile = OutputUtil.postfixFile(file, fileExt)
    FileUtils.writeStringToFile(outputFile, content, UTF_8)
  }
}