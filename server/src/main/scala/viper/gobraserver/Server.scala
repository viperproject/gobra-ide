package viper.gobraserver

import java.io.IOException
import java.net.Socket

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient

object Server {
  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      println("no port number provided")
      sys.exit(1)
      return
    }
    var port = -1
    try {
      port = Integer.parseInt(args.head)
    } catch {
      case _: Exception => {
        println("invalid port number")
        sys.exit(1)
        return
      }
    }

    runServer(port)
  }

  def runServer(port: Int): Unit = {
    // start listening on port
    try {
      val socket = new Socket("localhost", port)
      println(s"going to listen on port $port")

      val server: GobraServerService = new GobraServerService()
      val launcher = Launcher.createLauncher(server, classOf[LanguageClient], socket.getInputStream, socket.getOutputStream)
      server.connect(launcher.getRemoteProxy)
      // start listening on input stream in a new thread:
      val fut = launcher.startListening()
      // wait until stream is closed again
      fut.get()
    } catch {
      case e: IOException => println(s"IOException occurred: ${e.toString}")
    }
  }
}