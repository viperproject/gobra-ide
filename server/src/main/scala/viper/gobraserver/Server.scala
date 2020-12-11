// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import java.io.IOException
import java.net.{ServerSocket, Socket}
import java.util.concurrent.TimeUnit

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.Launcher.Builder

object Server {

  val copyright = "(c) Copyright ETH Zurich 2012 - 2020"

  val name = "Gobra Server"

  val version: String = {
    val buildRevision = BuildInfo.git("revision")
    val buildBranch = BuildInfo.git("branch")
    val buildVersion = s"$buildRevision${if (buildBranch == "master") "" else s"@$buildBranch"}"

    s"${BuildInfo.projectVersion} ($buildVersion)"
  }

  def main(args: Array[String]): Unit = {
    val config = new ServerConfig(args)
    val executor: DefaultGobraServerExecutionContext = new DefaultGobraServerExecutionContext(config.nThreads)
    println(s"Gobra server is using ${executor.nThreads} threads (excl. threads used by backends)")
    runServer(config)(executor)
    sys.exit(0)
  }

  private def createLauncher(gobraService: GobraServerService, socket: Socket)(executor: GobraServerExecutionContext): Launcher[IdeLanguageClient] =
    // Launcher.createLauncher cannot be called as we want to pass in an executor service
    // Hence, we directly use Launcher.Builder:
    new Builder[IdeLanguageClient]()
      .setLocalService(gobraService)
      .setRemoteInterface(classOf[IdeLanguageClient])
      .setInput(socket.getInputStream)
      .setOutput(socket.getOutputStream)
      .setExecutorService(executor.service)
      .create()

  private def runServer(config: ServerConfig)(executor: GobraServerExecutionContext): Unit = {
    try {
      val serverSocket = new ServerSocket(config.port)
      announcePort(serverSocket.getLocalPort)
      println(s"going to listen on port ${serverSocket.getLocalPort}")
      val socket = serverSocket.accept()
      println(s"client got connected")
      // TODO add support for multiple clients connecting to this server

      val server: GobraServerService = new GobraServerService()(executor)
      val launcher = createLauncher(server, socket)(executor)
      server.connect(launcher.getRemoteProxy)
      // start listening on input stream in a new thread:
      val future = launcher.startListening()
      // wait until stream is closed again
      future.get()
      println("listener thread from server has stopped")
      executor.service.shutdown()
      executor.service.awaitTermination(1, TimeUnit.SECONDS)
      println("executor service has been shut down")
    } catch {
      case e: IOException => println(s"IOException occurred: ${e.toString}")
    }
  }

  private def announcePort(port: Int): Unit = {
    // write port number in a predefined format to standard output such that clients can parse it
    // do not change this format without adapting clients such as the Gobra IDE client
    // regex for parsing: "<GobraServerPort:(\d+)>"
    println(s"<GobraServerPort:$port>")
  }
}
