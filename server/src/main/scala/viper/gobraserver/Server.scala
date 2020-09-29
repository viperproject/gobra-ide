// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import java.io.IOException
import java.util.NoSuchElementException
import java.net.Socket

import org.eclipse.lsp4j.jsonrpc.Launcher

object Server {
  def main(args: Array[String]): Unit = {
    try {
      val port = Integer.parseInt(args.head)

      runServer(port)

    } catch {
      case _: NoSuchElementException =>
        println("No port number provided")
        sys.exit(1)

      case _: NumberFormatException =>
        println("Invalid port number")
        sys.exit(1)
    }
  }

  def runServer(port: Int): Unit = {
    // start listening on port
    try {
      val socket = new Socket("localhost", port)
      println(s"going to listen on port $port")

      val server: GobraServerService = new GobraServerService()
      val launcher = Launcher.createLauncher(server, classOf[IdeLanguageClient], socket.getInputStream, socket.getOutputStream)
      server.connect(launcher.getRemoteProxy)
      // start listening on input stream in a new thread:
      val future = launcher.startListening()
      // wait until stream is closed again
      future.get()
    } catch {
      case e: IOException => println(s"IOException occurred: ${e.toString}")
    }
  }


}