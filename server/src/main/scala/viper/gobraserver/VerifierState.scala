// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import com.google.gson.Gson
import org.eclipse.lsp4j.{Diagnostic, Position, PublishDiagnosticsParams, Range, TextDocumentContentChangeEvent}
import viper.gobra.reporting.BackTranslator.BackTrackInfo
import viper.gobra.reporting.VerifierError
import viper.gobra.util.GobraExecutionContext
import viper.silver.ast.Program

import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.math.max

object FileType extends Enumeration {
  val Gobra, Go = Value
}

object VerifierState {
  private val gson: Gson = new Gson()

  var openFileUri: String = _

  /**
    * Tracks filechanges which happened during a verification.
    */
  var changes: List[(String, List[TextDocumentContentChangeEvent])] = List()

  var verificationRunning: Int = 0

  private var _client: Option[IdeLanguageClient] = None
  def client: Option[IdeLanguageClient] = _client

  def setClient(client: IdeLanguageClient): Unit = {
    _client = Some(client)
  }

  def submitVerificationJob(program: Program, backtrack: BackTrackInfo, startTime: Long, completedProgress: Int, verifierConfig: VerifierConfig)(implicit executor: GobraExecutionContext): Unit = {
    // simply call verify here without explicitly waiting on the result (or waiting for it in a runnable submitted
    // to the thread pool - in this case an entire thread from the thread pool would be occupied waiting for it).
    // executor.execute(() => GobraServer.verify(verifierConfig, program, backtrack, startTime))
    GobraServer.verifyAst(verifierConfig, program, backtrack, startTime, completedProgress)
  }
  
  /**
    * The verification information for a given file. This information is overwritten whenever a verification is performed that involves a given file.
    * When a verification is running this is an int representing the progress.
    * When no verification is running this is the verification result.
    */
  private val _verificationInformation = mutable.Map[String, Either[Int, OverallVerificationResult]]()

  def updateVerificationInformation(fileUris: Vector[String], info: Either[Int, OverallVerificationResult]): Unit = {
    fileUris.foreach(fileUri => {
      // only send out verification information if it changes
      val isDifferent = !_verificationInformation.get(fileUri).contains(info)
      if (isDifferent) {
        _verificationInformation += (fileUri -> info)
        sendVerificationInformation(fileUri)
      }
    })
  }

  def removeVerificationInformation(fileUris: Vector[String]): Unit = {
    fileUris.foreach(fileUri => _verificationInformation.remove(fileUri))
  }

  /**
    * Sends the verification progress if a verification is still running or the overall verification
    * result when the verification is finished.
    */
  def sendVerificationInformation(fileUri: String): Unit = VerifierState.client match {
    case Some(c) =>
      _verificationInformation.get(fileUri) match {
        case Some(Left(progress)) => c.verificationProgress(fileUri, progress)
        case Some(Right(result)) => c.overallResult(gson.toJson(result))
        case None => c.noVerificationInformation()
      }

    case None =>
  }
  
  
  /**
    * Diagnostics of the verification stored per file in a key value pair.
    */
  private val _diagnostics = mutable.Map[String, List[Diagnostic]]()

  def addDiagnostics(fileUri: String, diagnostics: List[Diagnostic]): Unit =
    _diagnostics += (fileUri -> diagnostics)

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
  private val _cachedDiagnostics = mutable.Map[String, mutable.Map[VerifierError, Diagnostic]]()

  def addDiagnosticsCache(fileUri: String, errors: List[VerifierError], diagnostics: List[Diagnostic]): Unit = {
    val diagnosticsMap = (errors zip diagnostics).toMap
    _cachedDiagnostics.get(fileUri) match {
      case Some(cachedDiagnostics) => _cachedDiagnostics += (fileUri -> (cachedDiagnostics ++ diagnosticsMap))
      case None => _cachedDiagnostics += (fileUri -> (mutable.Map[VerifierError, Diagnostic]() ++ diagnosticsMap))
    }
  }

  def getDiagnosticsCache(fileUri: String): mutable.Map[VerifierError, Diagnostic] = {
    _cachedDiagnostics.get(fileUri) match {
      case Some(cache) => cache
      case None => mutable.Map[VerifierError, Diagnostic]()
    }
  }

  def flushCachedDiagnostics(): Unit = _cachedDiagnostics.clear()


  /**
    * Publish all available diagnostics.
    */
  def publishDiagnostics(fileUri: String): Unit =
    client match {
      case Some(c) =>
        println(s"fileUri: ${fileUri}")
        val params = new PublishDiagnosticsParams(fileUri, getDiagnostics(fileUri).asJava)
        c.publishDiagnostics(params)
      case None =>
    }


  def translateDiagnostics(changes: List[TextDocumentContentChangeEvent], diagnostics: List[Diagnostic]): List[Diagnostic] = {
    var newDiagnostics = diagnostics
    
    changes.foreach(change => {
      val range = change.getRange

      // Position of File change
      var (cStartL, cStartC) = (Helper.startLine(range), Helper.startChar(range))
      var (cEndL, cEndC) = (Helper.endLine(range), Helper.endChar(range))

      newDiagnostics = change.getText match {
        case "" =>
          /**
            * Delete character or line case.
            */
          val deletedLines = cEndL - cStartL
          val deletedCharacters = max(cEndC - cStartC, 0)

          newDiagnostics.map(diagnostic => {
            val range = diagnostic.getRange

            // Position of the Diagnostic
            var (startL, startC) = (Helper.startLine(range), Helper.startChar(range))
            var (endL, endC) = (Helper.endLine(range), Helper.endChar(range))

            /**
              * On same line as Diagnostic.
              * Before the first character of the Diagnostic.
              */
            if (cEndC <= startC && cEndL == endL) {
              startC = startC - deletedCharacters

              /**
                * Delete line, so diagnostic may not start at the beginning of the line.
                */
              if (cStartL < cEndL && cStartL < startL) startC = startC + cStartC
            }
               
            /**
              * On same line as Diagnostic.
              * Between the start and end character of the Diagnostic.
              */
            if (cEndC <= endC && cEndL == endL) {
              endC = endC - deletedCharacters

              /**
                * Delete line, so the end character may need some offset resulting from characters
                * which were on the line the diagnostic gets moved to.
                */
              if (cStartL < cEndL) endC = endC + cStartC
            }

            /**
              * Lines deleted before the diagnostic.
              */
            if (cEndL <= startL) {
              startL = startL - deletedLines
            }
            if (cEndL <= endL) {
              endL = endL - deletedLines
            }

            val startPos = new Position(startL, startC)
            val endPos = new Position(endL, endC)
            new Diagnostic(new Range(startPos, endPos), diagnostic.getMessage, diagnostic.getSeverity, "")
            
          })
        case text =>
          /**
            * Characters were overwritten with Code Completion / Intellisense.
            * Need to remove those to not count letters multiple times.
            */
          val overwrittenCharacters = cEndC - cStartC

          /**
            * Add character or line case.
            */
          val addedLines = text.count(_=='\n')
          val numReturns = text.count(_=='\r')
          val addedCharacters = text.count(_!='\n') - numReturns - overwrittenCharacters

          newDiagnostics.map(diagnostic => {
            // Position of the Diagnostic
            var (startL, startC) = (diagnostic.getRange.getStart.getLine, diagnostic.getRange.getStart.getCharacter)
            var (endL, endC) = (diagnostic.getRange.getEnd.getLine, diagnostic.getRange.getEnd.getCharacter)

            /**
              * Line added before the diagnostic or at the same line
              * before the start character of the diagnostic.
              */
            if (cEndL < startL || (cStartC <= startC && cEndL == startL)) {
              startL = startL + addedLines
            }

            /**
              * Characters added on same line and before the start
              * of the diagnostic.
              */
            if (cStartC <= startC && cEndL == endL) {
              if (addedLines > 0) {
                startC = startC - cStartC
              } else {
                startC = startC + addedCharacters
              }
            }


            /**
              * Characters added on same line and before the end
              * of the diagnostic.
              */
            if (cEndC < endC && cEndL == endL) {
              if (addedLines > 0) {
                endC = endC - cEndC
                endL = endL + addedLines
              } else {
                endC = endC + addedCharacters
              }
            } else if (cEndL < endL) {
              endL = endL + addedLines
            }
            

            val startPos = new Position(startL, startC)
            val endPos = new Position(endL, endC)
            if (startL < 0 || startC < 0 || endL < 0 || endC < 0) {
              null
            } else {
              new Diagnostic(new Range(startPos, endPos), diagnostic.getMessage, diagnostic.getSeverity, "")
            }
          })
      }
      newDiagnostics = newDiagnostics.filter(_!=null)
    })
    newDiagnostics
  }

  def updateDiagnostics(fileUri: String, changes: List[TextDocumentContentChangeEvent]): Unit = {
    if (changes.isEmpty) return

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
