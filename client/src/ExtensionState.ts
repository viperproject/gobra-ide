// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import { LanguageClient, LanguageClientOptions, ServerOptions } from 'vscode-languageclient';
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as net from 'net';
import * as child_process from "child_process";
import { FileData, VerifierConfig, GobraSettings } from "./MessagePayloads";
import { Helper, FileSchemes } from "./Helper";
import { IdeEvents } from "./IdeEvents";
import { Verifier } from "./VerificationService";
import { CodePreviewProvider } from "./CodePreviewProvider";


export class State {
  public static client: LanguageClient;
  public static context: vscode.ExtensionContext;
  public static updatingGobraTools: boolean;

  public static viperPreviewProvider: CodePreviewProvider;
  public static internalPreviewProvider: CodePreviewProvider;

  public static runningVerifications: Set<string>;
  // tracks the verification requests which were made when a verification was already running.
  public static verificationRequests: Map<string, IdeEvents>;

  public static runningGoifications: Set<string>;
  public static runningGobrafications: Set<string>;

  public static verificationRequestTimeout: NodeJS.Timeout;

  public static verifierConfig: VerifierConfig;

  public static updateFileData(fileUri?: string): void {
    this.verifierConfig.fileData = new FileData();

    if (fileUri) this.verifierConfig.fileData.fileUri = fileUri;
  }

  public static updateConfiguration(): void {
    //State.verifierConfig.clientConfig = new ClientConfig(config);
    State.verifierConfig.gobraSettings = Helper.getGobraSettings();
  }

  public static setVerificationRequestTimeout(fileUri: string, event: IdeEvents): void {
    State.verificationRequestTimeout = setTimeout(() => {
      Verifier.verifyFile(fileUri, event);
      State.clearVerificationRequestTimeout();
    }, Helper.getTimeout());
  }

  public static clearVerificationRequestTimeout(): void {
    clearTimeout(State.verificationRequestTimeout);
    State.verificationRequestTimeout = null;
  }

  public static refreshVerificationRequestTimeout(): void {
    State.verificationRequestTimeout.refresh();
  }


  // creates the language client and starts the server
  public static startLanguageServer(context: vscode.ExtensionContext, fileSystemWatcher: vscode.FileSystemWatcher): Promise<void> {

    this.updatingGobraTools = false;

    this.runningVerifications = new Set<string>();
    this.verificationRequests = new Map<string, IdeEvents>();

    this.runningGoifications = new Set<string>();
    this.runningGobrafications = new Set<string>();

    this.verificationRequestTimeout = null;

    this.viperPreviewProvider = new CodePreviewProvider();
    vscode.workspace.registerTextDocumentContentProvider(FileSchemes.viper, this.viperPreviewProvider);

    this.internalPreviewProvider = new CodePreviewProvider();
    vscode.workspace.registerTextDocumentContentProvider(FileSchemes.internal, this.internalPreviewProvider);



    const serverBin = Helper.getServerJarPath(Helper.isNightly());
    // use the following serverBin when you want to directly use the compiled server jar:
    // const prefix = __dirname.split("client")[0];
    // const serverBin = path.join(prefix, 'server', 'target', 'scala-2.12', 'server.jar')

    let serverOptions: ServerOptions = () => State.startServerProcess(serverBin);

    // server binary was not found
    if (!fs.existsSync(serverBin)) {
      const msg = "The server binary " + serverBin + " does not exist. Please update Gobra Tools.";
      vscode.window.showErrorMessage(msg);
      return Promise.reject(msg);
    }

    let clientOptions: LanguageClientOptions = {
      // register server for gobra files
      documentSelector: [{ scheme: 'file', language: 'gobra' }, { scheme: 'file', language: 'go' }],
      synchronize: {
          fileEvents: fileSystemWatcher
      }
    }

    this.client = new LanguageClient('gobraServer', 'Gobra Server', serverOptions, clientOptions);

    // Start the client together with the server.
    const disposable = this.client.start();
    // Push the disposable to the context's subscriptions so that the
    // client can be deactivated on extension deactivation
    context.subscriptions.push(disposable);
    this.context = context;
    
    return State.client.onReady();
  }


  // creates a server for the given server binary
  private static async startServerProcess(serverBin: string): Promise<any> {
    // test whether java and z3 binaries can be used:
    console.log("Checking Java...");
    const javaPath = await Helper.getJavaPath();
    await Helper.spawn(javaPath, ["--version"]);
    console.log("Checking Z3...");
    const z3Path = Helper.getZ3Path(Helper.isNightly());
    await Helper.spawn(z3Path, ["--version"]);

    return new Promise((resolve, reject) => {
      let server = net.createServer((socket) => {
        resolve({reader: socket, writer: socket});
    
        // event listener for socket reacting to end
        socket.on('end', () => console.log("Disconnected"));
    
      }).on('error', (err) => {
        console.log("Error in server creation.");
      });

      // start Gobra Server given in binary
      console.log(`Starting Gobra Server with binary ${serverBin}`);
      server.listen(() => {
        let serverAddress = server.address() as net.AddressInfo;
        let processArgs = ['-Xss128m', '-jar', serverBin, serverAddress.port.toString()];
        let serverProcess = child_process.spawn(javaPath, processArgs);

        // Send raw output to a file (for testing purposes only, change or remove later)------------------
        let prefix = __dirname.substring(0, __dirname.length - 3);
        let logFile = prefix + "gobraServer.log";
        let logStream = fs.createWriteStream(logFile, { flags: 'w' });
        
        serverProcess.stdout.pipe(logStream);
        serverProcess.stderr.pipe(logStream);
        
        serverProcess.on('close', (code) => {
          console.log(`Gobra Server process has ended with return code ${code}`);
        });
        serverProcess.on('error', (err) => {
          console.log(`Gorba Server process has encountered an error: ${err}`);
          reject(err);
        });
	
        console.log(`Storing log in '${logFile}'`);
        // -----------------------------------------------------------------------------------------------

      })
    });
  }

  public static disposeServer(): Thenable<void> {
    if (this.client == null) {
      return Promise.resolve();
    }
    return this.client.stop();
  }

}

