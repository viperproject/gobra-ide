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

import scala.math.max




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

  // Filechanges mapping from file uri to the file changes
  private val _fileChanges = Map[String, FileChanges]()

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

  def translateDiagnostics(fileChanges: FileChanges, diagnostics: List[Diagnostic]): List[Diagnostic] = {
    var newDiagnostics = diagnostics
    
    fileChanges.ranges.foreach({ range =>
      val (cStartL, cStartC) = (range.startPos.getLine(), range.startPos.getCharacter())
      val (cEndL, cEndC) = (range.endPos.getLine(), range.endPos.getCharacter())

      newDiagnostics = range.text match {
        case "" =>
          // delete character case
          val deletedLines = cEndL - cStartL
          val deletedCharacters = max(cEndC - cStartC, 0)

          newDiagnostics.map(diagnostic => {
            var (startL, startC) = (diagnostic.getRange().getStart().getLine(), diagnostic.getRange().getStart().getCharacter())
            var (endL, endC) = (diagnostic.getRange().getEnd().getLine(), diagnostic.getRange().getEnd().getCharacter())


            if (cEndC <= startC && cEndL == endL) {
                startC = startC - deletedCharacters

                if (cStartL < cEndL && cStartL < startL) startC = startC + cStartC
            }
               
            if (cEndC <= endC && cEndL == endL) {
              endC = endC - deletedCharacters

              if (cStartL < cEndL) endC = endC + cStartC
            }

            if (cEndL <= startL) startL = startL - deletedLines
            if (cEndL <= endL) endL = endL - deletedLines

            val startPos = new Position(startL, startC)
            val endPos = new Position(endL, endC)
            new Diagnostic(new Range(startPos, endPos), diagnostic.getMessage(), diagnostic.getSeverity(), "")
          })
        case text =>
          // add character case
          val addedLines = text.count(_=='\n')
          val numReturns = text.count(_=='\r')
          val addedCharacters = text.count(_!='\n') - numReturns

          newDiagnostics.map(diagnostic => {
            var (startL, startC) = (diagnostic.getRange().getStart().getLine(), diagnostic.getRange().getStart().getCharacter())
            var (endL, endC) = (diagnostic.getRange().getEnd().getLine(), diagnostic.getRange().getEnd().getCharacter())

            if (cEndL < startL || (cStartC <= startC && cEndL == startL))
              startL = startL + addedLines


            if (cStartC <= startC && cEndL == endL) {
              if (addedLines > 0) {
                startC = startC - cStartC
              } else {
                startC = startC + addedCharacters
              }
            }

            if (cEndC <= endC && cEndL == endL) {
              if (addedLines > 0) {
                endC = endC - cEndC
              } else {
                endC = endC + addedCharacters
              }
            }

            if (cEndL <= endL) endL = endL + addedLines
            

            val startPos = new Position(startL, startC)
            val endPos = new Position(endL, endC)
            if (startL < 0 || startC < 0 || endL < 0 || endC < 0) {
              null
            } else {
              new Diagnostic(new Range(startPos, endPos), diagnostic.getMessage(), diagnostic.getSeverity(), "")
            }
          })
      }
      newDiagnostics = newDiagnostics.filter(_!=null)
    })
    newDiagnostics
  }

  def updateDiagnostics(fileChanges: FileChanges) {
    // return when no changes were made
    if (fileChanges.ranges.length == 0) return


    _diagnostics.get(fileChanges.fileUri) match {
      case Some((diagnostics, overallResult)) =>
        val newDiagnostics = translateDiagnostics(fileChanges, diagnostics)

        VerifierState.addDiagnostics(fileChanges.fileUri, newDiagnostics, overallResult)
        VerifierState.publishDiagnostics(fileChanges.fileUri)

      case None => println("no diagnostic") // do nothing when no diagnostics exist
    }
  }




  def getFileChanges(fileUri: String): FileChanges = {
    _fileChanges.get(fileUri) match {
      case Some(changes) => changes
      case None => new FileChanges(fileUri, Array())
    }
  }

  def addFileChanges(fileChanges: FileChanges) {
    _fileChanges.get(fileChanges.fileUri) match {
      case Some(changes) =>
        val fileUri = fileChanges.fileUri
        _fileChanges += (fileUri -> (new FileChanges(fileUri,changes.ranges ++ fileChanges.ranges)))
      case None => _fileChanges += (fileChanges.fileUri -> fileChanges)  
    }
  }

  def resetFileChanges(fileUri: String) {
    _fileChanges.remove(fileUri)
  }
}
