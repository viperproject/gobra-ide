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
import scala.collection.immutable.{Map => ImmMap}
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Queue
import collection.JavaConverters._

import viper.gobra.reporting.VerifierError

import scala.math.max




object VerifierState {
  private val gson: Gson = new Gson()

  var openFileUri: String = _

  private var _verificationNum: Int = 0
  def verificationNum: Int = _verificationNum
  def incVerificationNum() {
    _verificationNum = _verificationNum + 1
  }

  private val _jobQueue = Queue[VerifierConfig]()
  def jobQueue: Queue[VerifierConfig] = _jobQueue

  private var _client: Option[IdeLanguageClient] = None
  def client: Option[IdeLanguageClient] = _client

  def setClient(client: IdeLanguageClient): Unit = {
    _client = Some(client)
  }
  

  /**
    * The overall verfication result.
    */
  private val _overallResults = Map[String, OverallVerificationResult]()

  def addOverallResult(fileUri: String, overallResult: OverallVerificationResult) {
    _overallResults += (fileUri -> overallResult)
  }

  def sendOverallResult(fileUri: String) {
    client match {
      case Some(c) =>
        _overallResults.get(fileUri) match {
          case Some(overallResult) => c.overallResultNotification(gson.toJson(overallResult))
          case None => c.noVerificationResult()
        }
    }
  }

  /**
    * Diagnostics of the verification stored per file in a key value pair.
    */
  private val _diagnostics = Map[String, ImmMap[VerifierError, Diagnostic]]()

  def addDiagnostics(fileUri: String, errors: List[VerifierError], diagnostics: List[Diagnostic]) {
    val diagnosticsMap = (errors zip diagnostics).toMap
    _diagnostics += (fileUri -> diagnosticsMap)
  }

  def addDiagnosticsMap(fileUri: String, diagnosticsMap: ImmMap[VerifierError, Diagnostic]) {
    _diagnostics += (fileUri -> diagnosticsMap)
  }

  def getDiagnostics(fileUri: String): ImmMap[VerifierError, Diagnostic] = {
    _diagnostics.get(fileUri) match {
      case Some(diagnostics) => diagnostics
      case None => ImmMap[VerifierError, Diagnostic]()
    }
  }

  def removeDiagnostics(fileUri: String): Unit = _diagnostics.remove(fileUri)

  /**
    * Publish all available diagnostics.
    */
  def publishDiagnostics(fileUri: String) {
    client match {
      case Some(c) =>
        val diagnostics = getDiagnostics(fileUri).values.toList
        val params = new PublishDiagnosticsParams(fileUri, diagnostics.asJava)
        c.publishDiagnostics(params)
      case None =>
    }
  }


  def translateDiagnostics(fileChanges: FileChanges, diagnostics: List[Diagnostic]): List[Diagnostic] = {
    var newDiagnostics = diagnostics
    
    fileChanges.ranges.foreach(range => {
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

            if (cEndL <= startL) {
              startL = startL - deletedLines
            }
            if (cEndL <= endL) {
              endL = endL - deletedLines
            }

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

            if (cEndL < startL || (cStartC <= startC && cEndL == startL)) {
              startL = startL + addedLines
            }


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

            if (cEndL <= endL) {
              endL = endL + addedLines
            }
            

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
    if (fileChanges.ranges.length == 0) return

    val fileUri = fileChanges.fileUri

    _diagnostics.get(fileUri) match {
      case Some(diagnosticsMap) =>
        val (errs, diagnostics) = diagnosticsMap.toList.unzip
        val newDiagnostics = translateDiagnostics(fileChanges, diagnostics)
        VerifierState.addDiagnostics(fileUri, errs, newDiagnostics)
        publishDiagnostics(fileUri)
      case None =>
    }
  }
}
