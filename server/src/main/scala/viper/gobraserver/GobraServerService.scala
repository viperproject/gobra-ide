// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import java.util.concurrent.CompletableFuture

import com.google.gson.Gson
import org.eclipse.lsp4j.jsonrpc.services.{JsonNotification, JsonRequest}
import org.eclipse.lsp4j.{DidChangeTextDocumentParams, DidChangeWatchedFilesParams, DidCloseTextDocumentParams, DidOpenTextDocumentParams, DidSaveTextDocumentParams, InitializeParams, InitializeResult, MessageParams, MessageType, Range, ServerCapabilities, TextDocumentSyncKind}

import scala.jdk.CollectionConverters._
import scala.annotation.unused

class GobraServerService()(implicit executor: GobraServerExecutionContext) extends IdeLanguageClientAware {
  private val gson: Gson = new Gson()


  @JsonRequest(value = "initialize")
  def initialize(@unused params: InitializeParams): CompletableFuture[InitializeResult] = {
    println("initialize")
    val capabilities = new ServerCapabilities()
    // always send full text document for each notification:
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental)

    val options: List[String] = List()
    GobraServer.init(options)(executor)
    GobraServer.start()

    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  @JsonRequest(value = "shutdown")
  def shutdown(): CompletableFuture[AnyRef] = {
    println("shutdown")

    GobraServer.stop()

    CompletableFuture.completedFuture(null)
  }

  @JsonNotification(value = "initialized")
  def initialized(): Unit = {
    println("Initialized Server")
  }

  @JsonNotification(value = "exit")
  def exit(): Unit = {
    println("exit")

    GobraServer.delete()

    sys.exit()
  }

  // This is received when a setting is changed.
  @JsonNotification("$/setTraceNotification")
  def setTraceNotification(@unused params: Any): Unit = {
    println("Trace Notification arrived")
  }

  @JsonNotification("textDocument/didOpen")
  def didOpen(params: DidOpenTextDocumentParams): Unit = {
    println("didOpen")

    VerifierState.openFileUri = params.getTextDocument.getUri
  }

  @JsonNotification("textDocument/didChange")
  def didChange(params: DidChangeTextDocumentParams): Unit = {
    val fileUri = params.getTextDocument.getUri
    val changes = params.getContentChanges.asScala.toList

    VerifierState.updateDiagnostics(fileUri, changes)

    if (VerifierState.verificationRunning > 0) {
      VerifierState.changes = VerifierState.changes :+ (fileUri, changes)
    }
  }

  @JsonNotification("gobraServer/setOpenFileUri")
  def setOpenFileUri(fileUri: String): Unit = {
    VerifierState.openFileUri = fileUri
  }

  @JsonNotification("textDocument/didClose")
  def didClose(@unused params: DidCloseTextDocumentParams): Unit = {
    println("didClose")

    // val fileUri = params.getTextDocument.getUri
    // TODO: need to remove diagnostics and forget file in ViperServer
    // VerifierState.removeDiagnostics(fileUri)
  }

  @JsonNotification("textDocument/didSave")
  def didSave(@unused params: DidSaveTextDocumentParams): Unit = {
    println("didSave")
  }

  @JsonNotification("workspace/didChangeWatchedFiles")
  def didChangeWatchedFiles(@unused params: DidChangeWatchedFilesParams): Unit = {
    println("didChangeWatchedFiles")
  }

  @JsonNotification("gobraServer/verifyGobraFile")
  def verifyGobraFile(configJson: String): Unit = {
    println("verifyGobraFile")
    val config: VerifierConfig = gson.fromJson(configJson, classOf[VerifierConfig])

    VerifierState.updateVerificationInformation(config.fileData.fileUri, Left(0))
    GobraServer.preprocess(config)
  }

  @JsonNotification("gobraServer/verifyGoFile")
  def verifyGoFile(configJson: String): Unit = {
    println("verifyGoFile")

    val config: VerifierConfig = gson.fromJson(configJson, classOf[VerifierConfig])

    VerifierState.updateVerificationInformation(config.fileData.fileUri, Left(0))
    GobraServer.preprocessGo(config)
  }

  @JsonNotification("gobraServer/goifyFile")
  def goifyFile(fileDataJson: String): Unit = {
    println("goifyFile")
    val fileData: FileData = gson.fromJson(fileDataJson, classOf[FileData])

    GobraServer.goify(fileData)
    GobraServer.flushCache()
  }

  @JsonNotification("gobraServer/gobrafyFile")
  def gobrafyFile(fileDataJson: String): Unit = {
    println("gobrafyFile")
    val fileData: FileData = gson.fromJson(fileDataJson, classOf[FileData])

    GobraServer.gobrafy(fileData)
    GobraServer.flushCache()
  }


  @JsonNotification("gobraServer/changeFile")
  def changeFile(fileDataJson: String): Unit = {
    println("changeFile")
    val fileData: FileData = gson.fromJson(fileDataJson, classOf[FileData])

    VerifierState.openFileUri = fileData.fileUri

    VerifierState.publishDiagnostics(VerifierState.openFileUri)
    VerifierState.sendVerificationInformation(VerifierState.openFileUri)
  }

  @JsonNotification("gobraServer/flushCache")
  def flushCache(): Unit = {
    println("flushCache")
    GobraServer.flushCache()
    VerifierState.flushCachedDiagnostics()

    VerifierState.client match {
      case Some(c) => c.showMessage(new MessageParams(MessageType.Info, "Successfully flushed ViperServer Cache."))
      case None =>
    }
  }

  @JsonNotification("gobraServer/codePreview")
  def codePreview(previewDataJson: String): Unit = {
    println("codePreview")

    val previewData: PreviewData = gson.fromJson(previewDataJson, classOf[PreviewData])
    val selections = previewData.selections.map(selection => new Range(selection(0), selection(1))).toList

    GobraServer.codePreview(previewData.fileData, previewData.internalPreview, previewData.viperPreview, selections)(executor)
  }


  override def connect(client: IdeLanguageClient): Unit = {
    println("client is connected")
    VerifierState.setClient(client)
  }
}




