// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import { LanguageClient, LanguageClientOptions, ServerOptions, StreamInfo } from 'vscode-languageclient/node';
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as net from 'net';
import * as child_process from "child_process";
import * as readline from 'readline';
import * as os from 'os';
import { FileData, VerifierConfig } from "./MessagePayloads";
import { Helper, FileSchemes } from "./Helper";
import { IdeEvents } from "./IdeEvents";
import { Verifier } from "./VerificationService";
import { CodePreviewProvider } from "./CodePreviewProvider";
import { Location } from 'vs-verification-toolbox';


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

  public static verificationRequestTimeout: NodeJS.Timeout | null;

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
    if (State.verificationRequestTimeout != null) {
      clearTimeout(State.verificationRequestTimeout);
      State.verificationRequestTimeout = null;
    }
  }

  public static refreshVerificationRequestTimeout(): void {
    if (State.verificationRequestTimeout != null) {
      State.verificationRequestTimeout.refresh();
    }
  }


  // creates the language client and starts the server
  public static startLanguageServer(context: vscode.ExtensionContext, fileSystemWatcher: vscode.FileSystemWatcher, location: Location): Promise<void> {

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

    const serverBin = Helper.getServerJarPath(location);
    // use the following serverBin when you want to directly use the compiled server jar:
    // const prefix = __dirname.split("client")[0];
    // const serverBin = path.join(prefix, 'server', 'target', 'scala-2.12', 'server.jar')

    const serverOptions: ServerOptions = () => State.startServerProcess(location, serverBin);
    // use the following lines to connect to a server instance instead of starting a new one (e.g. for debugging purposes)
    /*
    const connectionInfo = {
      host: "localhost",
      port: 8080
    }
    const serverOptions: ServerOptions = () => State.connectToServer(location, connectionInfo);
    */

    // server binary was not found
    if (!fs.existsSync(serverBin)) {
      const msg = `The server binary ${serverBin} does not exist. Please update Gobra Tools.`;
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
  private static async startServerProcess(location: Location, serverBin: string): Promise<StreamInfo> {
    const javaPath = await State.checkDependenciesAndGetJavaPath(location);

    // spawn Gobra Server and get port number on which it is reachable:
    const portNr = await new Promise((resolve:(port: number) => void, reject) => {
      const portRegex = /<GobraServerPort:(\d+)>/;
      let portFound: boolean = false;
      function stdOutLineHandler(line: string): void {
        // check whether `line` contains the port number
        if (!portFound) {
          const match = line.match(portRegex);
          if (match != null && match[1] != null) {
            const port = Number(match[1]);
            if (port != NaN) {
              portFound = true;
              resolve(port);
            }
          }
        }
      }

      const processArgs = Helper.getServerProcessArgs(serverBin);
      const command = `"${javaPath}" ${processArgs}`; // processArgs is already escaped but escape javaPath as well.
      const tmpDir = os.tmpdir();
      Helper.log(`Gobra IDE: Running '${command}' (relative to '${tmpDir}')`);
      // enable shell mode such that arguments do not need to be passed as an array
      // see https://stackoverflow.com/a/45134890/1990080
      const serverProcess = child_process.spawn(command, [], { shell: true, cwd: tmpDir });
      // redirect stdout to readline which nicely combines and splits lines
      const rl = readline.createInterface({ input: serverProcess.stdout });
      rl.on('line', stdOutLineHandler);
      serverProcess.stdout.on('data', (data) => Helper.logServer(data));
      serverProcess.stderr.on('data', (data) => Helper.logServer(data));
      serverProcess.on('close', (code) => {
        Helper.log(`Gobra Server process has ended with return code ${code}`);
      });
      serverProcess.on('error', (err) => {
        const msg = `Gorba Server process has encountered an error: ${err}`
        Helper.log(msg);
        reject(msg);
      });
    });

    // connect to server
    return new Promise((resolve: (info: StreamInfo) => void, reject) => {
      const clientSocket = new net.Socket();
      clientSocket.connect(portNr, 'localhost', () => {
        Helper.log(`Connected to Gobra Server`);
        resolve({
          reader: clientSocket,
          writer: clientSocket
        });
      });
      clientSocket.on('error', (err) => {
        Helper.log(`Error occurred on connection to Gobra Server: ${err}`);
        reject(err);
      });
    });
  }

  // creates a server for the given server binary
  private static async connectToServer(location: Location, connectionInfo: net.NetConnectOpts): Promise<StreamInfo> {
    await State.checkDependenciesAndGetJavaPath(location);
    const socket = net.connect(connectionInfo);
    return {
      reader: socket,
      writer: socket
    };
  }

  private static async checkDependenciesAndGetJavaPath(location: Location): Promise<string> {
    // test whether java and z3 binaries can be used:
    Helper.log("Checking Java...");
    const javaPath = await Helper.getJavaPath();
    await Helper.spawn(javaPath, ["-version"]);
    Helper.log("Checking Z3...");
    const z3Path = Helper.getZ3Path(location);
    await Helper.spawn(z3Path, ["--version"]);
    return javaPath;
  }

  public static disposeServer(): Thenable<void> {
    if (this.client == null) {
      return Promise.resolve();
    }
    return this.client.stop();
  }

}

