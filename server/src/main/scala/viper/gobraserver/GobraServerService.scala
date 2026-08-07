// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.{ResponseError, ResponseErrorCode}
import org.eclipse.lsp4j.jsonrpc.services.{JsonNotification, JsonRequest}
import org.eclipse.lsp4j.{DidChangeTextDocumentParams, DidChangeWatchedFilesParams, DidCloseTextDocumentParams, DidOpenTextDocumentParams, DidSaveTextDocumentParams, InitializeParams, InitializeResult, MessageParams, MessageType, Range, ServerCapabilities, ServerInfo, TextDocumentSyncKind}

import scala.jdk.CollectionConverters._
import scala.annotation.unused
import scala.util.Try

class GobraServerService(config: ServerConfig)(implicit executor: GobraServerExecutionContext) extends IdeLanguageClientAware {

  @JsonRequest(value = "initialize")
  def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    // the client sends the version of the client-server communication protocol it implements as
    // part of the initialization options. Clients predating this version handshake do not send it:
    val clientProtocolVersion: Option[Int] = params.getInitializationOptions match {
      case obj: JsonObject if obj.has("protocolVersion") => Try(obj.get("protocolVersion").getAsInt).toOption
      case _ => None
    }
    if (!clientProtocolVersion.contains(Server.protocolVersion)) {
      val msg = s"The Gobra IDE extension (communication protocol version ${clientProtocolVersion.getOrElse(1)}) " +
        s"is incompatible with the installed Gobra server (communication protocol version ${Server.protocolVersion}). " +
        "Please update the Gobra IDE extension and the Gobra tools (command 'Gobra: Update Gobra Tools') to matching versions."
      // note that the exception has to be returned as a failed future. Directly throwing it would
      // cause lsp4j to respond with a generic internal error instead of this error message:
      return CompletableFuture.failedFuture(new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest, msg, null)))
    }

    val capabilities = new ServerCapabilities()
    // always send full text document for each notification:
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental)
    // advertise the protocol version this server implements such that the client can in turn
    // check it against the protocol version it expects. Servers predating the version handshake
    // do not advertise any version:
    val experimental = new JsonObject()
    experimental.addProperty("protocolVersion", Server.protocolVersion)
    capabilities.setExperimental(experimental)

    val options: List[String] = List("--logLevel", config.logLevel.levelStr)
    GobraServer.init(options)(executor)
    GobraServer.start()

    val result = new InitializeResult(capabilities)
    result.setServerInfo(new ServerInfo(Server.name, Server.version))
    CompletableFuture.completedFuture(result)
  }

  @JsonRequest(value = "shutdown")
  def shutdown(): CompletableFuture[AnyRef] = {
    GobraServer.stop()
    CompletableFuture.completedFuture(null)
  }

  @JsonNotification(value = "initialized")
  def initialized(): Unit = {}

  @JsonNotification(value = "exit")
  def exit(): Unit = {
    GobraServer.delete()
    sys.exit()
  }

  // This is received when a setting is changed.
  @JsonNotification("$/setTraceNotification")
  def setTraceNotification(@unused params: Any): Unit = {}

  @JsonNotification("textDocument/didOpen")
  def didOpen(params: DidOpenTextDocumentParams): Unit = {
    VerifierState.openFileUri = params.getTextDocument.getUri
  }

  @JsonNotification("textDocument/didChange")
  def didChange(params: DidChangeTextDocumentParams): Unit = {
    val fileUri = params.getTextDocument.getUri
    val changes = params.getContentChanges.asScala.toList

    VerifierState.updateDiagnostics(fileUri, changes, None)

    if (VerifierState.verificationRunning > 0) {
      VerifierState.changes = VerifierState.changes :+ (fileUri, changes)
    }
  }

  @JsonNotification("gobraServer/setOpenFileUri")
  def setOpenFileUri(fileData: FileData): Unit = {
    VerifierState.openFileUri = fileData.fileUri
  }

  @JsonNotification("textDocument/didClose")
  def didClose(@unused params: DidCloseTextDocumentParams): Unit = {
    // val fileUri = params.getTextDocument.getUri
    // TODO: need to remove diagnostics and forget file in ViperServer
    // VerifierState.removeDiagnostics(fileUri)
  }

  @JsonNotification("textDocument/didSave")
  def didSave(@unused params: DidSaveTextDocumentParams): Unit = {}

  @JsonNotification("workspace/didChangeWatchedFiles")
  def didChangeWatchedFiles(@unused params: DidChangeWatchedFilesParams): Unit = {}

  @JsonNotification("gobraServer/verify")
  def verify(config: VerifierConfig): Unit = {
    val fileUris = config.fileData.map(_.fileUri).toVector
    VerifierState.updateVerificationInformation(fileUris, Left(0))
    GobraServer.preprocess(config)
  }

  @JsonNotification("gobraServer/goifyFile")
  def goifyFile(fileData: FileData): Unit = {
    GobraServer.goify(fileData)
  }

  @JsonNotification("gobraServer/gobrafyFile")
  def gobrafyFile(fileData: FileData): Unit = {
    GobraServer.gobrafy(fileData)
  }


  @JsonNotification("gobraServer/changeFile")
  def changeFile(fileData: FileData): Unit = {
    VerifierState.openFileUri = fileData.fileUri
    VerifierState.publishDiagnostics(VerifierState.openFileUri, None)
    VerifierState.sendVerificationInformation(VerifierState.openFileUri)
  }

  @JsonNotification("gobraServer/flushCache")
  def flushCache(): Unit = {
    GobraServer.flushCache()
    VerifierState.flushCachedDiagnostics()

    VerifierState.client match {
      case Some(c) => c.showMessage(new MessageParams(MessageType.Info, "Successfully flushed ViperServer Cache."))
      case None =>
    }
  }

  @JsonNotification("gobraServer/codePreview")
  def codePreview(previewData: PreviewData): Unit = {
    val selections = previewData.selections.map(selection => new Range(selection(0), selection(1))).toList
    GobraServer.codePreview(previewData.fileData, previewData.internalPreview, previewData.viperPreview, selections)(executor)
  }


  override def connect(client: IdeLanguageClient): Unit = {
    VerifierState.setClient(client)
  }
}




