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

import scala.util.{ Success, Failure }
import scala.concurrent.ExecutionContext

class GobraServerService extends IdeLanguageClientAware {
  private val gson: Gson = new Gson()

  // thread being responsible for dequeuing jobs and starting the verification.
  private var verificationWorker: Thread = _

  implicit val executionContext = ExecutionContext.global


  @JsonRequest(value = "initialize")
  def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    println("initialize")
    val capabilities = new ServerCapabilities()
    // always send full text document for each notification:
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)

    var options: List[String] = List()
    GobraServer.init(options)
    GobraServer.start()

    verificationWorker = new Thread(new VerificationWorker())
    verificationWorker.start()

    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  @JsonRequest(value = "shutdown")
  def shutdown(): CompletableFuture[AnyRef] = {
    println("shutdown")

    verificationWorker.interrupt()
    verificationWorker.join()
    verificationWorker = null

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
  def setTraceNotification(params: Any): Unit = {
    println("Trace Notification arrived")
  }

  @JsonNotification("textDocument/didOpen")
  def didOpen(params: DidOpenTextDocumentParams): Unit = {
    println("didOpen")

    VerifierState.openFileUri = params.getTextDocument().getUri()  
  }
  
  @JsonNotification("textDocument/didChange")
  def didChange(params: DidChangeTextDocumentParams): Unit = {
    println("DidChange")
    println(params.getContentChanges())
  }

  @JsonNotification("textDocument/didClose")
  def didClose(params: DidCloseTextDocumentParams): Unit = {
    println("didClose")

    VerifierState.resetDiagnostics(params.getTextDocument().getUri())
  }

  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): Unit = {
    println("didSave")

  }

  @JsonNotification("gobraServer/verifyFile")
  def verifyFile(configJson: String): Unit = {
    println("verifyFile")
    val config: VerifierConfig = gson.fromJson(configJson, classOf[VerifierConfig])

    VerifierState.jobQueue.synchronized {
      VerifierState.jobQueue.enqueue(config)
      VerifierState.jobQueue.notify()
    }

    //GobraServer.verify(config)
  }


  @JsonNotification("gobraServer/changeFile")
  def changeFile(fileDataJson: String): Unit = {
    println("changeFile")
    val fileData: FileData = gson.fromJson(fileDataJson, classOf[FileData])

    VerifierState.openFileUri = fileData.fileUri

    VerifierState.publishDiagnostics(VerifierState.openFileUri)
    VerifierState.sendOverallResult(VerifierState.openFileUri)
  }

  override def connect(client: IdeLanguageClient): Unit = {
    println("client is connected")
    VerifierState.setClient(client)
  }
}




