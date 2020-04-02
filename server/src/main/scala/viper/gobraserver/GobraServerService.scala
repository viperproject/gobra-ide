package viper.gobraserver

import java.util.concurrent.CompletableFuture

import com.google.gson.Gson

import org.eclipse.lsp4j.jsonrpc.services.{
  JsonNotification,
  JsonRequest
}
import org.eclipse.lsp4j.{ 
  DidChangeTextDocumentParams,
  DidCloseTextDocumentParams,
  DidOpenTextDocumentParams,
  DidSaveTextDocumentParams,
  InitializeParams,
  InitializeResult,
  MessageParams,
  MessageType,
  ServerCapabilities,
  TextDocumentSyncKind
}
import org.eclipse.lsp4j.services.{
  LanguageClient,
  LanguageClientAware
}

class GobraServerService extends LanguageClientAware {
  private val verifierState: VerifierState = new VerifierState()
  private val gson: Gson = new Gson()


  @JsonRequest(value = "initialize")
  def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    println("initialize")
    val capabilities = new ServerCapabilities()
    // always send full text document for each notification:
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  @JsonRequest(value = "shutdown")
  def shutdown(): CompletableFuture[AnyRef] = {
    println("shutdown")
    CompletableFuture.completedFuture(null)
  }

  @JsonNotification(value = "exit")
  def exit(): Unit = {
    println("exit")
    sys.exit()
  }

  @JsonNotification("textDocument/didOpen")
  def didOpen(params: DidOpenTextDocumentParams): Unit = {
    println("didOpen")
    verifierState.getClient() match {
      case Some(c) => c.showMessage(new MessageParams(MessageType.Log, "File got opened"))
      case _ =>
    }
  }

  
  @JsonNotification("textDocument/didChange")
  def didChange(params: DidChangeTextDocumentParams): Unit = {}

  @JsonNotification("textDocument/didClose")
  def didClose(params: DidCloseTextDocumentParams): Unit = {}


  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): Unit = {
    println("didSave")
    verifierState.getClient() match {
      case Some(c) => c.showMessage(new MessageParams(MessageType.Info, "File got saved"))
      case _ =>
    }
  }

  @JsonRequest("gobraServer/verifyFile")
  def verifyFile(configJson: String): CompletableFuture[String] = {
    println("verifyFile")
    val config: VerifierConfig = gson.fromJson(configJson, classOf[VerifierConfig])
//    this.verifierState.setConfig(config)
    CompletableFuture.completedFuture(gson.toJson(this.verifierState.verify(config)))

  }


  override def connect(client: LanguageClient): Unit = {
    println("client is connected")
    this.verifierState.setClient(client)
  }
}




