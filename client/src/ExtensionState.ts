import { LanguageClient, LanguageClientOptions } from 'vscode-languageclient';
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as net from 'net';
import * as child_process from "child_process";
import { FileData, VerifierConfig, GobraSettings } from "./MessagePayloads";
import { Helper } from "./Helper";
import { IdeEvents } from "./IdeEvents";
import { Verifier } from "./VerificationService";


export class State {
  public static client: LanguageClient;
  public static context: vscode.ExtensionContext;
  public static disposableServer: vscode.Disposable;
  public static runningVerificationUri: string;
  public static updatingViperTools: boolean;

  public static runningVerifications: Set<string>;
  // tracks the verification requests which were made when a verification was already running.
  public static verificationRequests: Map<string, IdeEvents>;

  public static runningGoifications: Set<string>;
  public static runningGobrafications: Set<string>;

  public static verificationRequestTimeout: NodeJS.Timeout;

  public static verifierConfig: VerifierConfig;

  public static updateFileData(): void {
    this.verifierConfig.fileData = new FileData();
  }

  public static updateConfiguration(): void {
    //State.verifierConfig.clientConfig = new ClientConfig(config);
    State.verifierConfig.gobraSettings = Helper.getGobraSettings();
  }

  public static setVerificationRequestTimeout(fileUri: string, timeout: number, event: IdeEvents): void {
    State.verificationRequestTimeout = setTimeout(() => {
      Verifier.verifyFile(fileUri, event);
      State.clearVerificationRequestTimeout();
    }, timeout);
  }

  public static clearVerificationRequestTimeout(): void {
    clearTimeout(State.verificationRequestTimeout);
    State.verificationRequestTimeout = null;
  }

  public static refreshVerificationRequestTimeout(): void {
    State.verificationRequestTimeout.refresh();
  }


  // creates the language client and starts the server
  public static startLanguageServer(context: vscode.ExtensionContext, fileSystemWatcher: vscode.FileSystemWatcher): Promise<any> {
    this.context = context;
    this.runningVerificationUri = null;

    this.updatingViperTools = false;

    this.runningVerifications = new Set<string>();
    this.verificationRequests = new Map<string, IdeEvents>();

    this.runningGoifications = new Set<string>();
    this.runningGobrafications = new Set<string>();

    this.verificationRequestTimeout = null;

    let serverBin = State.context.asAbsolutePath(path.join('../', 'server', 'target', 'scala-2.12', 'server.jar'));

    let serverOptions = () => State.startServerProcess(serverBin);

    // server binary was not found
    if (!fs.existsSync(serverBin)) {
      vscode.window.showErrorMessage("The server binary " + serverBin + " does not exist. Please reinstall the Extension.");
      return;
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
    this.disposableServer = this.client.start();

    // check whether the server has started
    if (!this.disposableServer) {
      console.log("Error: Failed to start the client.");
    }
  }


  // creates a server for the given server binary
  private static startServerProcess(serverBin: string): Promise<any> {
    return new Promise((resolve, reject) => {
      let server = net.createServer((socket) => {
        resolve({reader: socket, writer: socket});
    
        // event listener for socket reacting to end
        socket.on('end', () => console.log("Disconnected"));
    
      }).on('error', (err) => {
        console.log("Error in server creation.");
      });
    
      // start Gobra Server given in binary
      console.log("Starting Gobra Server");
      server.listen(() => {
        let serverAddress = server.address() as net.AddressInfo;
        let processArgs = ['-Xss128m', '-jar', serverBin, serverAddress.port.toString()];
   
        let serverProcess = child_process.spawn('java', processArgs);


        // Send raw output to a file (for testing purposes only, change or remove later)------------------
				let logFile = this.context.asAbsolutePath('gobraServer.log');
				let logStream = fs.createWriteStream(logFile, { flags: 'w' });
	
				serverProcess.stdout.pipe(logStream);
				serverProcess.stderr.pipe(logStream);
	
        console.log(`Storing log in '${logFile}'`);
        // -----------------------------------------------------------------------------------------------
      })
    });
  }

  public static disposeServer(): Promise<any> {
    return new Promise((resolve, reject) => {
      console.log("Disposing Server");
      this.disposableServer.dispose();
      resolve();
    });
  }

}

