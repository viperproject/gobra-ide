import { State } from "./ExtensionState";
import { Helper, Commands, Texts, Color } from "./Helper";
import { StatusBar } from "./StatusBar";
import * as vscode from 'vscode';
import { URI } from 'vscode-uri';
import { VerifierConfig, OverallVerificationResult, FileData, FileChanges } from "./MessagePayloads";


export class Verifier {
  // insert fields defining the verifier
  public static verifyItem: StatusBar;

  public static initialize(verifierConfig: VerifierConfig): void {
    // Initialize Verification Button in Statusbar
    Verifier.verifyItem = new StatusBar(Texts.helloGobra, 10);
    Helper.registerCommand(Commands.verifyFile, Verifier.verifyFile, State.context);
    Verifier.verifyItem.setCommand(Commands.verifyFile, State.context);
    // add data of current file
    State.verifierConfig = verifierConfig;
    // register file changed listener
    State.context.subscriptions.push(vscode.window.onDidChangeActiveTextEditor(Verifier.changeFile));

    // register handler for file changes sending the ranges and actions to the client
    vscode.workspace.onDidChangeTextDocument(Verifier.sendFileChanges);

    // register the handler for the overall verification result notification
    State.client.onNotification(Commands.overallResultNotification, Verifier.handleOverallResultNotification)
    // register the handler for the no verification result notification
    State.client.onNotification(Commands.noVerificationResult, Verifier.handleNoResultNotification);
    // register the handler for the finished verification notification
    State.client.onNotification(Commands.finishedVerification, Verifier.handleFinishedVerificationNotification);
    // register the handler for the verification exception notification
    State.client.onNotification(Commands.verificationException, Verifier.handleFinishedVerificationNotification);
  }

  // verifies the file which is currently open in the editor
  public static verifyFile(): void {
    if (vscode.window.activeTextEditor && vscode.window.activeTextEditor.document) {
      let fileUri = Helper.getFileUri();

      if (!State.runningVerifications.has(fileUri)) {
      
        State.runningVerifications.add(fileUri);

        State.updateConfiguration();
        Verifier.verifyItem.addHourGlass();

        vscode.window.activeTextEditor.document.save().then((saved: boolean) => {
          console.log("sending verification request");
          State.client.sendNotification(Commands.verifyFile, Helper.configToJson(State.verifierConfig))
        });
      }
    }
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
  }


  public static changeFile(): void {
    // setting filedata to currently open filedata
    State.updateFileData();
    State.client.sendNotification(Commands.changeFile, Helper.fileDataToJson(State.verifierConfig.fileData));
  }


  public static sendFileChanges(changeEvent: vscode.TextDocumentChangeEvent): void {
    let fileUri = URI.file(changeEvent.document.fileName).toString();
    let ranges = changeEvent.contentChanges.map(Helper.createChangeRange);

    if (ranges.length == 0) return;

    let fileChanges = new FileChanges(fileUri, ranges);
    State.client.sendNotification(Commands.fileChanges, Helper.fileChangesToJson(fileChanges));
    console.log(Helper.fileChangesToJson(fileChanges));
  }

}


