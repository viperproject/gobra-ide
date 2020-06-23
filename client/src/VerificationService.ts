import { State } from "./ExtensionState";
import { Helper, Commands, ContributionCommands, Texts, Color } from "./Helper";
import { ProgressBar } from "./ProgressBar";
import * as vscode from 'vscode';
import * as fs from 'fs';
import { VerifierConfig, OverallVerificationResult, FileData, GobraSettings } from "./MessagePayloads";
import { IdeEvents } from "./IdeEvents";

import { Dependency, InstallerSequence, FileDownloader, ZipExtractor, withProgressInWindow, Location } from 'vs-verification-toolbox';

export class Verifier {
  public static verifyItem: ProgressBar;

  public static initialize(context: vscode.ExtensionContext, verifierConfig: VerifierConfig, fileUri: string, timeout: number): void {
    // add file data of current file to the state
    State.verifierConfig = verifierConfig;
    State.context = context;

    // Initialize Verification Button in Statusbar
    Verifier.verifyItem = new ProgressBar(Texts.helloGobra, 10);

    /**
      * Register Commands for Command Palette.
      */
    Helper.registerCommand(ContributionCommands.flushCache, Verifier.flushCache, State.context);
    Helper.registerCommand(ContributionCommands.goifyFile, Verifier.goifyFile, State.context);
    Helper.registerCommand(ContributionCommands.gobrafyFile, Verifier.gobrafyFile, State.context);
    Helper.registerCommand(ContributionCommands.verifyFile, Verifier.manualVerifyFile, State.context);
    Helper.registerCommand(ContributionCommands.updateGobraTools, () => Verifier.updateGobraTools(true), State.context);

    /**
      * Register Notification handlers for Gobra-Server notifications.
      */
    State.client.onNotification(Commands.overallResult, Verifier.handleOverallResultNotification)
    State.client.onNotification(Commands.verificationProgress, Verifier.handleVerificationProgressNotification);
    State.client.onNotification(Commands.noVerificationInformation, Verifier.handleNoVerificationInformationNotification);
    State.client.onNotification(Commands.verificationException, Verifier.handleVerificationExceptionNotification);

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

    // change of build version
    State.context.subscriptions.push(vscode.workspace.onDidChangeConfiguration(e => {
      if (e.affectsConfiguration("gobraSettings.buildVersion"))
        Verifier.updateGobraTools(true, Texts.changedBuildVersion);
    }))


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
    State.verificationRequests.delete(fileUri);


    State.clearVerificationRequestTimeout();

    // return when no text editor is active
    if (!vscode.window.activeTextEditor) return;

    // return when the gobra tools are currently being updated.
    if (State.updatingGobraTools) return;
    
    // only verify if it is a gobra file or a go file where the verification was manually invoked.
    if (!fileUri.endsWith(".gobra") && !(fileUri.endsWith(".go") && event == IdeEvents.Manual)) return;

    State.updateConfiguration();
    State.updateFileData(fileUri);

    // return if file is currently getting gobrafied.
    if (State.runningGobrafications.has(State.verifierConfig.fileData.filePath)) return
    
    if (!State.runningVerifications.has(fileUri)) {
      
      State.runningVerifications.add(fileUri);
      Verifier.verifyItem.progress(Helper.getFileName(fileUri), 0);

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
    * Verifies the File if it is in the verification requests queue.
    */
  private static reverifyFile(fileUri: string): void {
    if (State.verificationRequests.has(fileUri)) {
      let event = State.verificationRequests.get(fileUri);
      State.verificationRequests.delete(fileUri);
      Verifier.verifyFile(fileUri, event);
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
    * Update GobraTools by downloading them if necessary. 
    */
  public static async updateGobraTools(shouldUpdate: boolean, notificationText?: string): Promise<Location> {
    State.updatingGobraTools = true;


    let gobraToolsProvider = Helper.getGobraToolsProvider(Helper.isNightly());
    let gobraToolsPath = Helper.getGobraToolsPath();
    let boogiePath = Helper.getBoogiePath();
    let z3Path = Helper.getZ3Path();

    console.log(gobraToolsProvider);
    console.log(gobraToolsPath);
    console.log(boogiePath);
    console.log(z3Path);
    console.log(Helper.getServerJarPath());

    if (!fs.existsSync(gobraToolsPath)) {
      fs.mkdirSync(gobraToolsPath);
    }

    const gobraTools = new Dependency<"Gobra">(
      gobraToolsPath,
      ["Gobra",
        new InstallerSequence([
          new FileDownloader(gobraToolsProvider),
          new ZipExtractor("GobraTools")
        ])
      ]
    );

    const { result: location, didReportProgress } = await withProgressInWindow(
      shouldUpdate ? Texts.updatingGobraTools : Texts.installingGobraTools,
      listener => gobraTools.install("Gobra", shouldUpdate, listener)
    );

    if (Helper.isLinux || Helper.isMac) {
      fs.chmodSync(z3Path, '755');
      fs.chmodSync(boogiePath, '755');
      fs.chmodSync(boogiePath + ".exe", '755')
    }

    if (didReportProgress) {
      if (notificationText) {
        vscode.window.showInformationMessage(notificationText);
      } else if (shouldUpdate) {
        vscode.window.showInformationMessage(Texts.successfulUpdatingGobraTools);
      } else {
        vscode.window.showInformationMessage(Texts.successfulInstallingGobraTools);
      }
    }

    return location;
  }

  

  /**
    * Handler Functions handling notifications from Gobra-Server.
    */
   private static handleNoVerificationInformationNotification(): void {
    Verifier.verifyItem.setProperties(Texts.helloGobra, Color.white);

    let fileUri = Helper.getFileUri();

    if (!State.runningVerifications.has(fileUri)) {
      Verifier.verifyFile(fileUri, IdeEvents.Open);
    }
  }

  private static handleOverallResultNotification(jsonOverallResult: string): void {
    let overallResult: OverallVerificationResult = Helper.jsonToOverallResult(jsonOverallResult);

    State.runningVerifications.delete(overallResult.fileUri);

    if (overallResult.success) {
      Verifier.verifyItem.setProperties(overallResult.message, Color.green);
    } else {
      Verifier.verifyItem.setProperties(overallResult.message, Color.red);
    }

    Verifier.reverifyFile(overallResult.fileUri);
  }

  private static handleVerificationProgressNotification(fileUri: string, progress: number): void {
    Verifier.verifyItem.progress(Helper.getFileName(fileUri), progress);
  }


  private static handleVerificationExceptionNotification(fileUri: string): void {
    State.runningVerifications.delete(fileUri);

    Verifier.verifyItem.setProperties(Texts.helloGobra, Color.white);

    Verifier.reverifyFile(fileUri);
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


  private static delay(ms: number) {
    return new Promise( resolve => setTimeout(resolve, ms) );
}




}


