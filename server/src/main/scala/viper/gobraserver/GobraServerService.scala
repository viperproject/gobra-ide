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
  DidChangeWatchedFilesParams,
  InitializeParams,
  InitializeResult,
  ServerCapabilities,
  TextDocumentSyncKind,
  TextDocumentContentChangeEvent,
  MessageParams,
  MessageType
}

import scala.util.{ Success, Failure }
import collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

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
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental)

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
    Future {
      val fileUri = params.getTextDocument().getUri()
      val changes = params.getContentChanges().asScala.toList

      VerifierState.updateDiagnostics(fileUri, changes)

      if (VerifierState.verificationRunning) VerifierState.changes = VerifierState.changes :+ (fileUri, changes)
    }
  }

  @JsonNotification("textDocument/didClose")
  def didClose(params: DidCloseTextDocumentParams): Unit = {
    println("didClose")

    val fileUri = params.getTextDocument().getUri()
    // TODO: need to remove diagnostics and forget file in ViperServer
    //VerifierState.removeDiagnostics(fileUri)
  }

  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): Unit = {
    println("didSave")
  }

  @JsonNotification("workspace/didChangeWatchedFiles")
  def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = {
    println("didChangeWatchedFiles")
  }

  @JsonNotification("gobraServer/verifyGobraFile")
  def verifyGobraFile(configJson: String): Unit = {
    println("verifyGobraFile")
    val config: VerifierConfig = gson.fromJson(configJson, classOf[VerifierConfig])

    VerifierState.jobQueue.synchronized {
      VerifierState.jobQueue.enqueue((FileType.Gobra, config))
      VerifierState.jobQueue.notify()
    }
  }

  @JsonNotification("gobraServer/verifyGoFile")
  def verifyGoFile(configJson: String): Unit = {
    println("verifyGoFile")

    val config: VerifierConfig = gson.fromJson(configJson, classOf[VerifierConfig])

    VerifierState.jobQueue.synchronized {
      VerifierState.jobQueue.enqueue((FileType.Go, config))
      VerifierState.jobQueue.notify()
    }
  }

  @JsonNotification("gobraServer/goifyFile")
  def goifyFile(fileDataJson: String): Unit = {
    println("goifyFile")
    val fileData: FileData = gson.fromJson(fileDataJson, classOf[FileData])

    GobraServer.goify(fileData)
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
    VerifierState.sendOverallResult(VerifierState.openFileUri)
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


  override def connect(client: IdeLanguageClient): Unit = {
    println("client is connected")
    VerifierState.setClient(client)
  }
}




