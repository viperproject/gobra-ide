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
  ServerCapabilities,
  TextDocumentSyncKind
}
import org.eclipse.lsp4j.services.{
  LanguageClient,
  LanguageClientAware
}

class GobraServerService extends LanguageClientAware {
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

  @JsonNotification(value = "initialized")
  def initialized(): Unit = {
    println("Initialized Server")
  }

  @JsonNotification(value = "exit")
  def exit(): Unit = {
    println("exit")
    sys.exit()
  }

   /*
   * Every time a setting is changed in the client, a setTraceNotification message
   * is sent. At the moment this is not used for anything.
   */
  @JsonNotification("$/setTraceNotification")
  def setTraceNotification(params: Any): Unit = {
    println("Trace Notification arrived")
  }

  @JsonNotification("textDocument/didOpen")
  def didOpen(params: DidOpenTextDocumentParams): Unit = {
    println("didOpen")
  }
  
  @JsonNotification("textDocument/didChange")
  def didChange(params: DidChangeTextDocumentParams): Unit = {}

  @JsonNotification("textDocument/didClose")
  def didClose(params: DidCloseTextDocumentParams): Unit = {
    println("didClose")
  }

  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): Unit = {
    println("didSave")
  }

  @JsonRequest("gobraServer/verifyFile")
  def verifyFile(configJson: String): CompletableFuture[String] = {
    println("verifyFile")
    val config: VerifierConfig = gson.fromJson(configJson, classOf[VerifierConfig])
    CompletableFuture.completedFuture(gson.toJson(VerifierState.verify(config)))

  }

  @JsonNotification("gobraServer/changeFile")
  def changeFile(fileDataJson: String): Unit = {
    println("changeFile")
    val fileData: FileData = gson.fromJson(fileDataJson, classOf[FileData])
    VerifierState.resetDiagnostics()

    fileData match {
      case FileData(_, uri) => VerifierState.publishDiagnostics(uri)
    }
  }


  override def connect(client: LanguageClient): Unit = {
    println("client is connected")
    VerifierState.setClient(client)
  }
}




