package viper.gobraserver

import com.google.gson.Gson

import org.eclipse.lsp4j.{
    Diagnostic,
    Position,
    Range,
    DiagnosticSeverity,
    PublishDiagnosticsParams
}

import scala.collection.mutable.Map
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Queue
import collection.JavaConverters._




object VerifierState {
  private val gson: Gson = new Gson()

  var openFileUri: String = _

  private val _jobQueue = Queue[VerifierConfig]()
  def jobQueue: Queue[VerifierConfig] = _jobQueue

  private var _client: Option[IdeLanguageClient] = None
  def client: Option[IdeLanguageClient] = _client

  def setClient(client: IdeLanguageClient): Unit = {
    _client = Some(client)
  }
  

  // Diagnostics mapping from file uri to a list of diagnostics and overall verification results
  private val _diagnostics = Map[String, (List[Diagnostic], OverallVerificationResult)]()

  def getDiagnostics(fileUri: String): List[Diagnostic] = {
    _diagnostics.get(fileUri) match {
      case Some((diagnostics, _)) => diagnostics
      case None => Nil
    }
  }

  def getOverallResult(fileUri: String): Option[OverallVerificationResult] = {
    _diagnostics.get(fileUri) match {
      case Some((_, overallResult)) => Some(overallResult)
      case None => None
    }
  }

  def resetDiagnostics(fileUri: String) {
    _diagnostics.get(fileUri) match {
      case Some(_) => _diagnostics.remove(fileUri)
      case None =>
    }
  }

  def addDiagnostics(fileUri: String, diagnostics: List[Diagnostic], overallResult: OverallVerificationResult) {
    _diagnostics += (fileUri -> (diagnostics, overallResult))
  }

  def publishDiagnostics(fileUri: String) {
    client match {
      case Some(c) =>
        val params = new PublishDiagnosticsParams(fileUri, getDiagnostics(fileUri).asJava)
        c.publishDiagnostics(params)
      case None =>
    }
  }

  def sendOverallResult(fileUri: String) {
    client match {
      case Some(c) =>
        getOverallResult(fileUri) match {
          case Some(overallResult) => c.overallResultNotification(gson.toJson(overallResult))
          case None => c.noVerificationResult()
        }
    }
  }

  def sendFinishedVerification(fileUri: String) {
    client match {
      case Some(c) => c.finishedVerification(fileUri)
      case None =>
    }
  }

  def updateDiagnostics(fileChanges: FileChanges) {
    // return when no changes were made
    if (fileChanges.ranges.length == 0) return

    _diagnostics.get(fileChanges.fileUri) match {
      case Some((diagnostics, overallResult)) =>
        fileChanges.ranges.foreach({ change =>
          val (changeStartLine, changeStartCharacter) = (change.startPos.getLine(), change.startPos.getCharacter())
          val (changeEndLine, changeEndCharacter) = (change.endPos.getLine(), change.endPos.getCharacter())
        
          val addedLines = change.text.count(_=='\n')
          val numReturns = change.text.count(_=='\r')
          val addedCharacters = change.text.count(_!='\n') - numReturns
          val (deletedLines, deletedCharacters) = change.text match {
            case "" => (changeEndLine-changeStartLine, changeEndCharacter-changeStartCharacter)
            case _ => (0, 0)
          }

          val newDiagnostics = diagnostics.map(diagnostic => {
            var (startLine, startCharacter) = (diagnostic.getRange().getStart().getLine(), diagnostic.getRange().getStart().getCharacter())
            var (endLine, endCharacter) = (diagnostic.getRange().getEnd().getLine(), diagnostic.getRange().getEnd().getCharacter())

            
            if (changeStartCharacter <= startCharacter && changeStartLine == startLine)
              startCharacter = startCharacter + addedCharacters - deletedCharacters

            if (changeStartLine <= startLine) startLine = startLine + addedLines - deletedLines
            

            if (changeEndCharacter <= endCharacter && changeEndLine == endLine)
              endCharacter = endCharacter + addedCharacters - deletedCharacters

            if (changeStartLine <= endLine) endLine = endLine + addedLines - deletedLines

            val startPos = new Position(startLine, startCharacter)
            val endPos = new Position(endLine, endCharacter)

            
            if (changeEndCharacter == 0 && changeStartCharacter > 0 && startLine == changeStartLine && change.text == "") {
              // deleted line and ended up in upper line with text in it
              null
            } else if (startLine >= 0 && startCharacter >= 0 && endLine >= 0 && endCharacter >= 0) {
              new Diagnostic(new Range(startPos, endPos), diagnostic.getMessage(), diagnostic.getSeverity(), "")
            } else {
              null
            }
          })

          VerifierState.addDiagnostics(fileChanges.fileUri, newDiagnostics.filter(_ != null), overallResult)
          VerifierState.publishDiagnostics(fileChanges.fileUri)
        })

        

      case None => // do nothing when no diagnostics exist
    }
  }
}
