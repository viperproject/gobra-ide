// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import { LanguageClient, LanguageClientOptions, ServerOptions, StreamInfo } from 'vscode-languageclient';
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as net from 'net';
import * as child_process from "child_process";
import * as readline from 'readline';
import { FileData, IsolationData, VerifierConfig } from "./MessagePayloads";
import { Helper, FileSchemes } from "./Helper";
import { IdeEvents } from "./IdeEvents";
import { Verifier } from "./VerificationService";
import { CodePreviewProvider } from "./CodePreviewProvider";
import { Location } from 'vs-verification-toolbox';
import { URI } from 'vscode-uri';


export class State {
  public static client: LanguageClient;
  public static context: vscode.ExtensionContext;
  public static updatingGobraTools: boolean;

  public static viperPreviewProvider: CodePreviewProvider;
  public static internalPreviewProvider: CodePreviewProvider;

  /** currently running verifications which are identified by the list of fileUris that are stringified*/
  private static runningVerifications: Set<string>;
  // tracks the verification requests which were made when a verification was already running.
  private static verificationRequests: Map<string, IdeEvents>;

  private static runningGoifications: Set<string>;
  private static runningGobrafications: Set<string>;

  public static verificationRequestTimeout: NodeJS.Timeout | null;

  public static verifierConfig: VerifierConfig;

  public static isFileInvolvedInRunningVerification(fileUri: URI): Boolean {
    for(let runningVerification of State.runningVerifications) {
      const decodedFileUris = JSON.parse(runningVerification) as string[];
      if (decodedFileUris.some(f => f == fileUri.toString())) {
        return true;
      }
    }
    return false;
  }

  private static encodeUris(fileUris: URI[]): string {
    return JSON.stringify(fileUris.map(uri => State.encodeUri(uri)));
  }
  private static encodeUri(fileUri: URI): string {
    return fileUri.toString();
  }

  private static addUris(set: Set<string>, fileUris: URI[]) {
    set.add(State.encodeUris(fileUris));
  }
  private static containsUris(set: Set<string>, fileUris: URI[]): Boolean {
    return set.has(State.encodeUris(fileUris));
  }
  private static removeUris(set: Set<string>, fileUris: URI[]) {
    set.delete(State.encodeUris(fileUris));
  }

  private static addUri(set: Set<string>, fileUri: URI) {
    set.add(State.encodeUri(fileUri));
  }
  private static containsUri(set: Set<string>, fileUri: URI): Boolean {
    return set.has(State.encodeUri(fileUri));
  }
  private static removeUri(set: Set<string>, fileUri: URI) {
    set.delete(State.encodeUri(fileUri));
  }

  public static addRunningVerification(fileUris: URI[]) {
    State.addUris(State.runningVerifications, fileUris);
  }
  public static containsRunningVerification(fileUris: URI[]): Boolean {
    return State.containsUris(State.runningVerifications, fileUris);
  }
  public static removeRunningVerification(fileUris: URI[]) {
    State.removeUris(State.runningVerifications, fileUris);
  }

  public static addRunningGoifications(fileUri: URI) {
    State.addUri(State.runningGoifications, fileUri);
  }
  public static containsRunningGoifications(fileUri: URI): Boolean {
    return State.containsUri(State.runningGoifications, fileUri);
  }
  public static removeRunningGoifications(fileUri: URI) {
    State.removeUri(State.runningGoifications, fileUri);
  }

  public static addRunningGobrafications(fileUri: URI) {
    State.addUri(State.runningGobrafications, fileUri);
  }
  public static containsRunningGobrafications(fileUri: URI): Boolean {
    return State.containsUri(State.runningGobrafications, fileUri);
  }
  public static removeRunningGobrafications(fileUri: URI) {
    State.removeUri(State.runningGobrafications, fileUri);
  }

  public static addVerificationRequests(fileUris: URI[], ev: IdeEvents) {
    State.verificationRequests.set(State.encodeUris(fileUris), ev);
  }
  public static containsVerificationRequests(fileUris: URI[]): Boolean {
    return State.verificationRequests.has(State.encodeUris(fileUris));
  }
  public static getVerificationRequestsEvent(fileUris: URI[]): IdeEvents | undefined {
    return State.verificationRequests.get(State.encodeUris(fileUris));
  }
  public static removeVerificationRequests(fileUris: URI[]) {
    State.verificationRequests.delete(State.encodeUris(fileUris));
  }

  public static updateFileData(fileUris: URI[], isolationData: IsolationData[]): void {
    const fileData: FileData[] = fileUris.map(fileUri => new FileData(fileUri));
    this.verifierConfig.fileData = fileData;
    this.verifierConfig.isolate = isolationData;
  }

  public static updateConfiguration(): void {
    //State.verifierConfig.clientConfig = new ClientConfig(config);
    State.verifierConfig.gobraSettings = Helper.getGobraSettings();
  }

  public static setVerificationRequestTimeout(fileUri: URI, event: IdeEvents): void {
    State.verificationRequestTimeout = setTimeout(() => {
      Verifier.verify(fileUri, event);
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
    if (serverBin.error != null) {
      vscode.window.showErrorMessage(serverBin.error);
      return Promise.reject(serverBin.error);
    }
    // use the following serverBin when you want to directly use the compiled server jar:
    // const prefix = __dirname.split("client")[0];
    // const serverBin = path.join(prefix, 'server', 'target', 'scala-2.12', 'server.jar')

    const serverOptions: ServerOptions = () => State.startServerProcess(location, serverBin.path);
    // use the following lines to connect to a server instance instead of starting a new one (e.g. for debugging purposes)
    /*
    const connectionInfo = {
      host: "localhost",
      port: 8080
    }
    const serverOptions: ServerOptions = () => State.connectToServer(location, connectionInfo);
    */

    let clientOptions: LanguageClientOptions = {
      // register server for gobra files
      documentSelector: [{ scheme: 'file', language: 'gobra' }, { scheme: 'file', language: 'go' }],
      synchronize: {
          fileEvents: fileSystemWatcher
      }
    }

    // the id (i.e. `gobraServer`) has to match the first part of the settings item on tracing the communication between
    // client and server
    this.client = new LanguageClient('gobraServer', 'Gobra IDE - Server Communication', serverOptions, clientOptions);

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
    const cwd = await Helper.getJavaCwd();

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
      Helper.log(`Gobra IDE: Running '${command}' (relative to '${cwd}')`);
      // enable shell mode such that arguments do not need to be passed as an array
      // see https://stackoverflow.com/a/45134890/1990080
      const serverProcess = child_process.spawn(command, [], { shell: true, cwd: cwd });
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
    if (z3Path.error != null) {
      vscode.window.showErrorMessage(z3Path.error);
      return Promise.reject(z3Path.error);
    }
    await Helper.spawn(z3Path.path, ["--version"]);
    return javaPath;
  }

  public static disposeServer(): Thenable<void> {
    if (this.client == null) {
      return Promise.resolve();
    }
    return this.client.stop();
  }

}

