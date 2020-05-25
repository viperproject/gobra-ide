import { State } from "./ExtensionState";
import { Helper, Commands, Texts, Color } from "./Helper";
import { StatusBarButton } from "./StatusBarButton";
import * as vscode from 'vscode';
import { VerifierConfig, OverallVerificationResult, FileData } from "./MessagePayloads";
import { IdeEvents } from "./IdeEvents";


export class Verifier {
  // insert fields defining the verifier
  public static verifyItem: StatusBarButton;
  public static cacheFlushItem: StatusBarButton;

  public static initialize(verifierConfig: VerifierConfig, fileUri: string, timeout: number): void {
    // Initialize Verification Button in Statusbar
    Verifier.verifyItem = new StatusBarButton(Texts.helloGobra, 10);

    // Initialize Flush Cache Button in Statusbar
    Verifier.cacheFlushItem = new StatusBarButton(Texts.flushCache, 20);
    Helper.registerCommand(Commands.flushCache, Verifier.flushCache, State.context);
    Verifier.cacheFlushItem.setCommand(Commands.flushCache, State.context);

    // add data of current file
    State.verifierConfig = verifierConfig;
    // register file changed listener
    State.context.subscriptions.push(vscode.window.onDidChangeActiveTextEditor(Verifier.changeFile));

    // verify file which triggered the activation of the plugin
    Verifier.verifyFile(fileUri.toString(), IdeEvents.Open);
    // register event which verifies files which get newly opened
    State.context.subscriptions.push(vscode.workspace.onDidOpenTextDocument(document => {
      Verifier.verifyFile(document.uri.toString(), IdeEvents.Open)
    }));
    
    // register event which verifies files when they get saved
    State.context.subscriptions.push(vscode.workspace.onDidSaveTextDocument(document => {
      Verifier.verifyFile(document.uri.toString(), IdeEvents.Save);
    }));

    // register event which verifies files when a filechange is made
    State.context.subscriptions.push(vscode.workspace.onDidChangeTextDocument(change => {
      // don't set timeout when file was saved
      if (change.contentChanges.length == 0) return;

      if (State.verificationRequestTimeout) {
        State.refreshVerificationRequestTimeout();
      } else {
        State.setVerificationRequestTimeout(change.document.uri.toString(), timeout, IdeEvents.FileChange);
      }
    }));

    // register the handler for the overall verification result notification
    State.client.onNotification(Commands.overallResultNotification, Verifier.handleOverallResultNotification)
    // register the handler for the no verification result notification
    State.client.onNotification(Commands.noVerificationResult, Verifier.handleNoResultNotification);
    // register the handler for the finished verification notification
    State.client.onNotification(Commands.finishedVerification, Verifier.handleFinishedVerificationNotification);
    // register the handler for the verification exception notification
    State.client.onNotification(Commands.verificationException, Verifier.handleFinishedVerificationNotification);
  }

  public static test(name: string): void {
    console.log(name);
  }

  // verifies the file with the given fileUri
  public static verifyFile(fileUri: string, event: IdeEvents): void {
    // only verify if it is a gobra file
    if (!fileUri.endsWith(".gobra")) return;

    if (!State.runningVerifications.has(fileUri)) {
      
      State.runningVerifications.add(fileUri);

      State.updateConfiguration();
      Verifier.verifyItem.addHourGlass();

      vscode.window.activeTextEditor.document.save().then((saved: boolean) => {
        console.log("sending verification request");
        State.client.sendNotification(Commands.verifyFile, Helper.configToJson(State.verifierConfig))
      });
    } else {
      if (!State.verificationRequests.has(fileUri) && event != IdeEvents.Save) {
        State.verificationRequests.set(fileUri, event);
      }
    }
  }

  // flushes cache of ViperServer and also all diagnostics.
  public static flushCache(): void {
    State.client.sendNotification(Commands.flushCache);
  }


  private static handleOverallResultNotification(jsonOverallResult: string): void {
    let overallResult: OverallVerificationResult = Helper.jsonToOverallResult(jsonOverallResult);
    if (overallResult.success) {
      Verifier.verifyItem.setProperties(overallResult.message, Color.green);
    } else {
      Verifier.verifyItem.setProperties(overallResult.message, Color.red);
    }

    let fileUri = Helper.getFileUri();
    if (State.runningVerifications.has(fileUri)) {
      Verifier.verifyItem.addHourGlass();
    }
  }

  private static handleNoResultNotification(): void {
    Verifier.verifyItem.setProperties(Texts.helloGobra, Color.white);

    let fileUri = Helper.getFileUri();
    if (State.runningVerifications.has(fileUri)) {
      Verifier.verifyItem.addHourGlass();
    }
  }

  private static handleFinishedVerificationNotification(fileUri: string): void {
    State.runningVerifications.delete(fileUri);

    if (Helper.getFileUri() == fileUri) {
      Verifier.verifyItem.removeHourGlass();
    }

    if (State.verificationRequests.has(fileUri)) {
      let event = State.verificationRequests.get(fileUri);
      State.verificationRequests.delete(fileUri);
      Verifier.verifyFile(fileUri, event);
    }
  }


  public static changeFile(): void {
    // setting filedata to currently open filedata
    State.updateFileData();
    State.client.sendNotification(Commands.changeFile, Helper.fileDataToJson(State.verifierConfig.fileData));
    State.clearVerificationRequestTimeout();
  }
}


