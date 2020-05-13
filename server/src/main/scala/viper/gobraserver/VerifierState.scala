package viper.gobraserver

import com.google.gson.Gson

import org.eclipse.lsp4j.{
    Diagnostic,
    Position,
    Range,
    DiagnosticSeverity,
    PublishDiagnosticsParams,
    TextDocumentContentChangeEvent
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
  private val _diagnostics = Map[String, List[Diagnostic]]()

  def addDiagnostics(fileUri: String, diagnostics: List[Diagnostic]) {
    _diagnostics += (fileUri -> diagnostics)
  }

  def getDiagnostics(fileUri: String): List[Diagnostic] = {
    _diagnostics.get(fileUri) match {
      case Some(diagnostics) => diagnostics
      case None => List()
    }
  }

  def removeDiagnostics(fileUri: String): Unit = _diagnostics.remove(fileUri)


  /**
    * Cache of the previously used diagnostics.
    */
  private val _cachedDiagnostics = Map[String, Map[VerifierError, Diagnostic]]()

  def addDiagnosticsCache(fileUri: String, errors: List[VerifierError], diagnostics: List[Diagnostic]) {
    val diagnosticsMap = (errors zip diagnostics).toMap
    _cachedDiagnostics.get(fileUri) match {
      case Some(diagnostics) => _cachedDiagnostics += (fileUri -> (diagnostics ++ diagnosticsMap))
      case None => _cachedDiagnostics += (fileUri -> (Map[VerifierError, Diagnostic]() ++ diagnosticsMap))
    }
  }

  def getDiagnosticsCache(fileUri: String): Map[VerifierError, Diagnostic] = {
    _cachedDiagnostics.get(fileUri) match {
      case Some(cache) => cache
      case None => Map[VerifierError, Diagnostic]()
    }
  }


  /**
    * Publish all available diagnostics.
    */
  def publishDiagnostics(fileUri: String) {
    client match {
      case Some(c) =>
        val params = new PublishDiagnosticsParams(fileUri, getDiagnostics(fileUri).asJava)
        c.publishDiagnostics(params)
      case None =>
    }
  }


  def translateDiagnostics(changes: List[TextDocumentContentChangeEvent], diagnostics: List[Diagnostic]): List[Diagnostic] = {
    var newDiagnostics = diagnostics
    
    changes.foreach(change => {
      val range = change.getRange()

      var (cStartL, cStartC) = (Helper.startLine(range), Helper.startChar(range))
      var (cEndL, cEndC) = (Helper.endLine(range), Helper.endChar(range))

      newDiagnostics = change.getText() match {
        case "" =>
          // delete character case
          val deletedLines = cEndL - cStartL
          val deletedCharacters = max(cEndC - cStartC, 0)

          newDiagnostics.map(diagnostic => {
            val range = diagnostic.getRange()

            var (startL, startC) = (Helper.startLine(range), Helper.startChar(range))
            var (endL, endC) = (Helper.endLine(range), Helper.endChar(range))

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

            if (cEndC < endC && cEndL == endL) {
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

  def updateDiagnostics(fileUri: String, changes: List[TextDocumentContentChangeEvent]) {
    if (changes.length == 0) return

    _diagnostics.get(fileUri) match {
      case Some(diagnostics) =>
        val newDiagnostics = translateDiagnostics(changes, diagnostics)
        addDiagnostics(fileUri, newDiagnostics)
        publishDiagnostics(fileUri)
      case None =>
    }

    _cachedDiagnostics.get(fileUri) match {
      case Some(diagnosticsMap) =>
        val (errs, diagnostics) = diagnosticsMap.toList.unzip
        val newDiagnostics = translateDiagnostics(changes, diagnostics)
        addDiagnosticsCache(fileUri, errs, newDiagnostics)
      case None =>
    }
  }
}
