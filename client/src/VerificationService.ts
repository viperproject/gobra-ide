import { State } from "./ExtensionState";
import { Helper, Commands, Texts, Color } from "./Helper";
import { StatusBarButton } from "./StatusBarButton";
import * as vscode from 'vscode';
import { VerifierConfig, OverallVerificationResult, FileData } from "./MessagePayloads";
import { IdeEvents } from "./IdeEvents";


export class Verifier {
  public static verifyItem: StatusBarButton;
  public static cacheFlushItem: StatusBarButton;

  public static initialize(verifierConfig: VerifierConfig, fileUri: string, timeout: number): void {
    // add file data of current file to the state
    State.verifierConfig = verifierConfig;

    // Initialize Verification Button in Statusbar
    Verifier.verifyItem = new StatusBarButton(Texts.helloGobra, 10);

    /**
      * Register Commands for Command Palette.
      */
    Helper.registerCommand(Commands.flushCache, Verifier.flushCache, State.context);
    Helper.registerCommand(Commands.goifyFile, Verifier.goifyFile, State.context);


    /**
      * Register Notification handlers for Gobra-Server notifications.
      */
    State.client.onNotification(Commands.overallResultNotification, Verifier.handleOverallResultNotification)
    State.client.onNotification(Commands.noVerificationResult, Verifier.handleNoResultNotification);
    State.client.onNotification(Commands.finishedVerification, Verifier.handleFinishedVerificationNotification);
    State.client.onNotification(Commands.verificationException, Verifier.handleFinishedVerificationNotification);

    State.client.onNotification(Commands.finishedGoifying, Verifier.handleFinishedGoifyingNotification);


    /**
      * Register VSCode Event listeners.
      */
    State.context.subscriptions.push(vscode.window.onDidChangeActiveTextEditor(Verifier.changeFile));
    // open event
    State.context.subscriptions.push(vscode.workspace.onDidOpenTextDocument(document => {
      Verifier.verifyFile(document.uri.toString(), IdeEvents.Open)
    }));
    // save event
    State.context.subscriptions.push(vscode.workspace.onDidSaveTextDocument(document => {
      Verifier.verifyFile(document.uri.toString(), IdeEvents.Save);
    }));
    // filechange event
    State.context.subscriptions.push(vscode.workspace.onDidChangeTextDocument(change => {
      // don't set timeout when file was saved
      if (change.contentChanges.length == 0) return;

      if (State.verificationRequestTimeout) {
        State.refreshVerificationRequestTimeout();
      } else {
        State.setVerificationRequestTimeout(change.document.uri.toString(), timeout, IdeEvents.FileChange);
      }
    }));


    // verify file which triggered the activation of the plugin
    Verifier.verifyFile(fileUri.toString(), IdeEvents.Open);    
  }


  /**
    * Verifies the file with the given fileUri
    */
  public static verifyFile(fileUri: string, event: IdeEvents): void {
    State.clearVerificationRequestTimeout();
    
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

  /**
    * Transform the file with the given fileUri to a Go file with the goified annotations.
    * Open the Goified file when the Goification has terminated and succeeded.
    */
  public static goifyFile(): void {
    State.updateFileData();

    let fileUri = State.verifierConfig.fileData.fileUri;
    let filePath = State.verifierConfig.fileData.filePath;

    // only goify if it is a gobra file
    if (!fileUri.endsWith(".gobra")) return;

    if (!State.runningGoifications.has(fileUri)) {
      State.runningGoifications.add(fileUri);

      vscode.window.activeTextEditor.document.save().then((saved: boolean) => {
        console.log("sending goification request");
        State.client.sendNotification(Commands.goifyFile, Helper.fileDataToJson(State.verifierConfig.fileData))
      })
    } else {
      vscode.window.showInformationMessage("There is already a Goification running for file " + filePath);
    }
  }


  /**
    * Flushes cache of ViperServer and also all diagnostics.
    */
  public static flushCache(): void {
    State.client.sendNotification(Commands.flushCache);
  }


  /**
    * Send focus change information to Gobra-Server.
    */
   public static changeFile(): void {
    // setting filedata to currently open filedata
    State.updateFileData();
    State.client.sendNotification(Commands.changeFile, Helper.fileDataToJson(State.verifierConfig.fileData));
    State.clearVerificationRequestTimeout();
  }


  /**
    * Handler Functions handling notifications from Gobra-Server.
    */
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

  private static handleFinishedGoifyingNotification(fileUri: string, success: boolean): void {
    State.runningGoifications.delete(fileUri);

    if (success) {
      vscode.window.showTextDocument(vscode.Uri.parse(fileUri + ".go"));
    } else {
      vscode.window.showErrorMessage("An error occured during the Goification of " + vscode.Uri.parse(fileUri).fsPath);
    }
  }
}


