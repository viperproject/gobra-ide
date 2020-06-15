import { State } from "./ExtensionState";
import { Helper, Commands, ContributionCommands, Texts, Color } from "./Helper";
import { StatusBarButton } from "./StatusBarButton";
import * as vscode from 'vscode';
import { VerifierConfig, OverallVerificationResult, FileData } from "./MessagePayloads";
import { IdeEvents } from "./IdeEvents";
import * as pathHelper from 'path';

// TODO: change this to import module instead of file in my project. also remove index file in project and dependencies and util folder
//import { Dependency, InstallerSequence, FileDownloader, ZipExtractor } from "./dependencies";


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
    Helper.registerCommand(ContributionCommands.flushCache, Verifier.flushCache, State.context);
    Helper.registerCommand(ContributionCommands.goifyFile, Verifier.goifyFile, State.context);
    Helper.registerCommand(ContributionCommands.gobrafyFile, Verifier.gobrafyFile, State.context);
    Helper.registerCommand(ContributionCommands.verifyFile, Verifier.manualVerifyFile, State.context);
    Helper.registerCommand(ContributionCommands.updateViperTools, Verifier.updateViperTools, State.context);

    /**
      * Register Notification handlers for Gobra-Server notifications.
      */
    State.client.onNotification(Commands.overallResultNotification, Verifier.handleOverallResultNotification)
    State.client.onNotification(Commands.noVerificationResult, Verifier.handleNoResultNotification);
    State.client.onNotification(Commands.finishedVerification, Verifier.handleFinishedVerificationNotification);
    State.client.onNotification(Commands.verificationException, Verifier.handleFinishedVerificationNotification);

    State.client.onNotification(Commands.finishedGoifying, Verifier.handleFinishedGoifyingNotification);
    State.client.onNotification(Commands.finishedGobrafying, Verifier.handleFinishedGobrafyingNotification);

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
    * Verifies the currently opened file
    */
  public static manualVerifyFile(): void {
    State.updateConfiguration();
    Verifier.verifyFile(State.verifierConfig.fileData.fileUri, IdeEvents.Manual);
  }

  /**
    * Verifies the file with the given fileUri
    */
  public static verifyFile(fileUri: string, event: IdeEvents): void {
    State.clearVerificationRequestTimeout();

    // return when no text editor is active
    if (!vscode.window.activeTextEditor) return;
    
    // only verify if it is a gobra file or a go file where the verification was manually invoked.
    if (!fileUri.endsWith(".gobra") && !(fileUri.endsWith(".go") && event == IdeEvents.Manual)) return;
    
    if (!State.runningVerifications.has(fileUri)) {
      
      State.runningVerifications.add(fileUri);
      
      State.updateConfiguration();
      
      Verifier.verifyItem.addHourGlass();

      vscode.window.activeTextEditor.document.save().then((saved: boolean) => {
        console.log("sending verification request");

        if (fileUri.endsWith(".gobra")) {
          State.client.sendNotification(Commands.verifyGobraFile, Helper.configToJson(State.verifierConfig));
        } else {
          State.client.sendNotification(Commands.verifyGoFile, Helper.configToJson(State.verifierConfig));
        }
      });
    } else {
      if (!State.verificationRequests.has(fileUri) && event != IdeEvents.Save) {
        State.verificationRequests.set(fileUri, event);
      }
    }
  }

  /**
    * Transform the currently open file to a Go file with the goified annotations.
    * Open the Goified file when the Goification has terminated and succeeded.
    */
  public static goifyFile(): void {
    State.updateFileData();

    let fileUri = State.verifierConfig.fileData.fileUri;
    let filePath = State.verifierConfig.fileData.filePath;

    // only goify if it is a gobra file
    if (!fileUri.endsWith(".gobra")) {
      vscode.window.showErrorMessage("Can only Goify Gobra files!");
      return;
    } 
    

    if (!State.runningGoifications.has(fileUri)) {
      State.runningGoifications.add(fileUri);

      vscode.window.activeTextEditor.document.save().then((saved: boolean) => {
        console.log("sending goification request");
        State.client.sendNotification(Commands.goifyFile, Helper.fileDataToJson(State.verifierConfig.fileData));
      })
    } else {
      vscode.window.showInformationMessage("There is already a Goification running for file " + filePath);
    }
  }


  /**
    * Transform the currently open file to a Gobra file with proof annotations.
    * Open the Gobrafied file when the Gobrafication has terminated and succeeded.
    */
  public static gobrafyFile(): void {
    State.updateFileData();

    let fileUri = State.verifierConfig.fileData.fileUri;
    let filePath = State.verifierConfig.fileData.filePath;

    // only gobrafy if it is a go file
    if (!fileUri.endsWith(".go")) {
      vscode.window.showErrorMessage("Can only Gobrafy Go files!");
      return;
    }
    
    if (!State.runningGobrafications.has(filePath)) {
      State.runningGobrafications.add(filePath);

      vscode.window.activeTextEditor.document.save().then((saved: boolean) => {
        console.log("sending gobrafication request");
        State.client.sendNotification(Commands.gobrafyFile, Helper.fileDataToJson(State.verifierConfig.fileData));
      })
    } else {
      vscode.window.showInformationMessage("There is already a Gobrafication running for file " + filePath);
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
    * Update ViperTools by downloading them if necessary. 
    */
  public static async updateViperTools(): Promise<any> {
    console.log("updating vipertools");



    //let viperToolsPath = pathHelper.join(boogiePath, "ViperToolWin.zip");
    //console.log(viperToolsPath);
    /*
    const myDependency = new Dependency<"remote">(
      "D:\\Daten_Silas\\Desktop",
      ["remote",
        new InstallerSequence([
          new FileDownloader("http://viper.ethz.ch/downloads/ViperToolsWin.zip"),
          new ZipExtractor("testViperTools")
        ])
      ]
    );

    console.log("created dependency");
    await myDependency.ensureInstalled("remote");
    */
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
    } else {
      Verifier.verifyFile(fileUri, IdeEvents.Open);
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

  private static handleFinishedGobrafyingNotification(oldFilePath: string, newFilePath: string, success: boolean): void {
    State.runningGobrafications.delete(oldFilePath);

    if (success) {
      vscode.window.showTextDocument(vscode.Uri.file(newFilePath));
    } else {
      vscode.window.showErrorMessage("An error occured during the Gobrafication of " + oldFilePath);
    }
  }




}