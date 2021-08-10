// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import { State } from "./ExtensionState";
import { Helper, Commands, ContributionCommands, Texts, Color, PreviewUris, BuildChannel } from "./Helper";
import { ProgressBar } from "./ProgressBar";
import * as vscode from 'vscode';
import * as fs from 'fs';
import { VerifierConfig, OverallVerificationResult, PreviewData } from "./MessagePayloads";
import { IdeEvents } from "./IdeEvents";

import { Dependency, withProgressInWindow, Location, DependencyInstaller, RemoteZipExtractor, GitHubZipExtractor, LocalReference, ConfirmResult, Success } from 'vs-verification-toolbox';

export class Verifier {
  public static verifyItem: ProgressBar;

  public static initialize(context: vscode.ExtensionContext, verifierConfig: VerifierConfig, fileUri: string): void {
    // add file data of current file to the state
    State.verifierConfig = verifierConfig;
    State.context = context;

    // Initialize Verification Button in Statusbar
    Verifier.verifyItem = new ProgressBar(Texts.helloGobra, 10);

    /**
      * Register Commands for Command Palette.
      */
    Helper.registerCommand(ContributionCommands.flushCache, Verifier.flushCache, context);
    Helper.registerCommand(ContributionCommands.goifyFile, Verifier.goifyFile, context);
    Helper.registerCommand(ContributionCommands.gobrafyFile, Verifier.gobrafyFile, context);
    Helper.registerCommand(ContributionCommands.verifyFile, Verifier.manualVerifyFile, context);
    Helper.registerCommand(ContributionCommands.updateGobraTools, () => Verifier.updateGobraTools(context, true), context);
    Helper.registerCommand(ContributionCommands.showViperCodePreview, Verifier.showViperCodePreview, context);
    Helper.registerCommand(ContributionCommands.showInternalCodePreview, Verifier.showInternalCodePreview, context);
    Helper.registerCommand(ContributionCommands.showJavaPath, () => Verifier.showJavaPath(), context);

    /**
      * Register Notification handlers for Gobra-Server notifications.
      */
    State.client.onNotification(Commands.overallResult, Verifier.handleOverallResultNotification)
    State.client.onNotification(Commands.verificationProgress, Verifier.handleVerificationProgressNotification);
    State.client.onNotification(Commands.noVerificationInformation, Verifier.handleNoVerificationInformationNotification);
    State.client.onNotification(Commands.verificationException, Verifier.handleVerificationExceptionNotification);

    State.client.onNotification(Commands.finishedGoifying, Verifier.handleFinishedGoifyingNotification);
    State.client.onNotification(Commands.finishedGobrafying, Verifier.handleFinishedGobrafyingNotification);
    State.client.onNotification(Commands.finishedViperCodePreview, Verifier.handleFinishedViperPreviewNotification);
    State.client.onNotification(Commands.finishedInternalCodePreview, Verifier.handleFinishedInternalPreviewNotification);

    /**
      * Register VSCode Event listeners.
      */
    State.context.subscriptions.push(vscode.window.onDidChangeActiveTextEditor(editor => {
      if (editor && editor.document.uri.toString() === PreviewUris.viper.toString()) {
        State.viperPreviewProvider.setDecorations(PreviewUris.viper);
      } else if (editor && editor.document.uri.toString() == PreviewUris.internal.toString()) {
        State.internalPreviewProvider.setDecorations(PreviewUris.internal);
      } else {
        Verifier.changeFile();
      }
    }));
    // open event
    State.context.subscriptions.push(vscode.workspace.onDidOpenTextDocument(document => {
      if (Helper.isAutoVerify()) Verifier.verifyFile(document.uri.toString(), IdeEvents.Open);
    }));
    // save event
    State.context.subscriptions.push(vscode.workspace.onDidSaveTextDocument(document => {
      if (Helper.isAutoVerify()) Verifier.verifyFile(document.uri.toString(), IdeEvents.Save);
    }));
    // filechange event
    State.context.subscriptions.push(vscode.workspace.onDidChangeTextDocument(change => {
      
      if (change.document.uri.toString() === PreviewUris.viper.toString() || change.document.uri.toString() === PreviewUris.internal.toString()) {
        vscode.window.showTextDocument(change.document.uri, { preview: false, preserveFocus: false }).then(() => {
          vscode.commands.executeCommand('workbench.action.closeActiveEditor').then(() => {
            State.viperPreviewProvider.setDecorations(change.document.uri);
          });
        });
        return;
      }

      // don't set timeout when auto verification is off
      if (!Helper.isAutoVerify()) return;

      // don't set timeout when file was saved
      if (change.contentChanges.length == 0) return;

      if (State.verificationRequestTimeout) {
        State.refreshVerificationRequestTimeout();
      } else {
        State.setVerificationRequestTimeout(change.document.uri.toString(), IdeEvents.FileChange);
      }
    }));

    // change of build version
    State.context.subscriptions.push(vscode.workspace.onDidChangeConfiguration(e => {
      if (e.affectsConfiguration("gobraSettings.buildVersion"))
        Verifier.updateGobraTools(context, true, Texts.changedBuildVersion);
    }))


    // verify file which triggered the activation of the plugin
    if (Helper.isAutoVerify()) Verifier.verifyFile(fileUri.toString(), IdeEvents.Open);    
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
        Helper.log("sending verification request");

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
    const event = State.verificationRequests.get(fileUri)
    if (event && Helper.isAutoVerify()) {
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
      
      const editor = vscode.window.activeTextEditor;
      if (editor) {
        editor.document.save().then((saved: boolean) => {
          Helper.log("sending goification request");
          State.client.sendNotification(Commands.goifyFile, Helper.fileDataToJson(State.verifierConfig.fileData));
        })
      } else {
        Helper.log("saving document for goifying was not possible");
      }
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

      const editor = vscode.window.activeTextEditor;
      if (editor) {
        editor.document.save().then((saved: boolean) => {
          Helper.log("sending gobrafication request");
          State.client.sendNotification(Commands.gobrafyFile, Helper.fileDataToJson(State.verifierConfig.fileData));
        })
      } else {
        Helper.log("saving document for gobrafying was not possible");
      }
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
  public static async updateGobraTools(context: vscode.ExtensionContext, shouldUpdate: boolean, notificationText?: string): Promise<Location> {
    async function confirm(): Promise<ConfirmResult> {
      if (shouldUpdate || Helper.assumeYes()) {
        // do not ask user
        return ConfirmResult.Continue;
      } else {
        const confirmation = await vscode.window.showInformationMessage(
          Texts.installingGobraToolsConfirmationMessage,
          Texts.installingGobraToolsConfirmationYesButton,
          Texts.installingGobraToolsConfirmationNoButton);
        if (confirmation === Texts.installingGobraToolsConfirmationYesButton) {
          return ConfirmResult.Continue;
        } else {
          // user has dismissed message without confirming
          return ConfirmResult.Cancel;
        }
      }
    }
    
    State.updatingGobraTools = true;
    const selectedChannel = Helper.getBuildChannel();
    const dependency = await this.getDependency(context);
    Helper.log(`Ensuring dependencies for build channel ${selectedChannel}`);
    const { result: installationResult, didReportProgress } = await withProgressInWindow(
      shouldUpdate ? Texts.updatingGobraTools : Texts.ensuringGobraTools,
      listener => dependency.install(selectedChannel, shouldUpdate, listener, confirm)
    ).catch(Helper.rethrow(`Downloading and unzipping the Gobra Tools has failed`));

    if (!(installationResult instanceof Success)) {
      throw new Error(Texts.gobraToolsInstallationDenied);
    }

    const location = installationResult.value;
    if (Helper.isLinux || Helper.isMac) {
      const z3Path = Helper.getZ3Path(location);
      const boogiePath = Helper.getBoogiePath(location);
      const boogieExePath = `${boogiePath}.exe`;
      fs.chmodSync(z3Path, '755');
      if (fs.existsSync(boogiePath)) {
        fs.chmodSync(boogiePath, '755');
      }
      if (fs.existsSync(boogieExePath)) {
        fs.chmodSync(boogieExePath, '755');
      }
    }

    if (didReportProgress) {
      if (notificationText) {
        vscode.window.showInformationMessage(notificationText);
      } else if (shouldUpdate) {
        vscode.window.showInformationMessage(Texts.successfulUpdatingGobraTools);
      } else {
        vscode.window.showInformationMessage(Texts.successfulEnsuringGobraTools);
      }
    }

    return location;
  }

  private static async getDependency(context: vscode.ExtensionContext): Promise<Dependency<BuildChannel>> {
    const buildChannelStrings = Object.keys(BuildChannel);
    const buildChannels = buildChannelStrings.map(c =>
      // Convert string to enum. See https://stackoverflow.com/a/17381004/2491528
      BuildChannel[c as keyof typeof BuildChannel]);
        
    // note that `installDestination` is only used if tools actually have to be downloaded and installed there, i.e. it is 
    // not used for build channel "Local":
    const installDestination = context.globalStorageUri.fsPath;
    const installers = await Promise.all(buildChannels
      .map<Promise<[BuildChannel, DependencyInstaller]>>(async c => 
        [c, await this.getDependencyInstaller(context, c)])
      );
    return new Dependency<BuildChannel>(
      installDestination,
      ...installers
    );
  }

  private static getDependencyInstaller(context: vscode.ExtensionContext, buildChannel: BuildChannel): Promise<DependencyInstaller> {
    if (buildChannel == BuildChannel.Local) {
        return this.getLocalDependencyInstaller();
    } else {
        return this.getRemoteDependencyInstaller(context, buildChannel);
    }
  }

  private static async getLocalDependencyInstaller(): Promise<DependencyInstaller> {
    return new LocalReference(Helper.getLocalGobraToolsPath());
  }

  private static get buildChannelSubfolderName(): string {
    return "GobraTools";
  }

  private static async getRemoteDependencyInstaller(context: vscode.ExtensionContext, buildChannel: BuildChannel): Promise<DependencyInstaller> {
    const gobraToolsRawProviderUrl = Helper.getGobraToolsProvider(buildChannel === BuildChannel.Nightly);
    // note that `gobraToolsProvider` might be one of the "special" URLs as specified in the README (i.e. to a GitHub releases asset):
    const gobraToolsProvider = Helper.parseGitHubAssetURL(gobraToolsRawProviderUrl);
    
    const folderName = this.buildChannelSubfolderName; // folder name to which ZIP will be unzipped to
    if (gobraToolsProvider.isGitHubAsset) {
      // provider is a GitHub release
      const token = Helper.getGitHubToken();
      return new GitHubZipExtractor(gobraToolsProvider.getUrl, folderName, token);
    } else {
      // provider is a regular resource on the Internet
      const url = await gobraToolsProvider.getUrl();
      return new RemoteZipExtractor(url, folderName);
    }
}


  /**
    * Shows the preview of the selected code in the translated Viper code.
    */
  public static showViperCodePreview(): void {
    let selections = Helper.getSelections();

    State.updateFileData();
    const editor = vscode.window.activeTextEditor;
    if (editor) {
      editor.document.save().then((saved: boolean) => {
        State.client.sendNotification(Commands.codePreview, Helper.previewDataToJson(new PreviewData(State.verifierConfig.fileData, false, true, selections)));
      });
    } else {
      Helper.log("saving document for showing Viper Code Preview was not possible");
    }
  }

  /**
    * Shows the preview of the selected code in the translated Internal representation. 
    */
  public static showInternalCodePreview(): void {
    let selections = Helper.getSelections();

    State.updateFileData();
    const editor = vscode.window.activeTextEditor;
    if (editor) {
      editor.document.save().then((saved: boolean) => {
        State.client.sendNotification(Commands.codePreview, Helper.previewDataToJson(new PreviewData(State.verifierConfig.fileData, true, false, selections)));
      });
    } else {
      Helper.log("saving document for showing Internal Code Preview was not possible");
    }
  }

  /**
   * Displays an information popup listing the path to the selected Java binary.
   */
  public static async showJavaPath(): Promise<void> {
    const javaPath = await Helper.getJavaPath();
    const javaVersion = await Helper.spawn(javaPath, ["-version"]);
    // at leat on macOS, stdout is empty and the version is in stderr. Thus, simply concatenate them:
    await vscode.window.showInformationMessage(Texts.javaLocation(javaPath, javaVersion.stdout.concat(javaVersion.stderr)));
  }

  /**
    * Handler Functions handling notifications from Gobra-Server.
    */
   private static handleNoVerificationInformationNotification(): void {
    Verifier.verifyItem.setProperties(Texts.helloGobra, Color.white);

    let fileUri = Helper.getFileUri();

    if (!State.runningVerifications.has(fileUri) && Helper.isAutoVerify()) {
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
      vscode.window.showTextDocument(vscode.Uri.file(newFilePath)).then(editor => {
        if (Helper.isAutoVerify()) Verifier.verifyFile(editor.document.uri.toString(), IdeEvents.Open);
      });
    } else {
      vscode.window.showErrorMessage("An error occured during the Gobrafication of " + oldFilePath);
    }
  }


  private static handleFinishedViperPreviewNotification(ast: string, highlightedJson: string): void {
    let highlightedPositions = Helper.jsonToHighlightingPositions(highlightedJson);
    State.viperPreviewProvider.updateCodePreview(PreviewUris.viper, ast, highlightedPositions);
  }

  private static handleFinishedInternalPreviewNotification(internal: string, highlightedJson: string): void {
    let highlightedPositions = Helper.jsonToHighlightingPositions(highlightedJson);
    State.internalPreviewProvider.updateCodePreview(PreviewUris.internal, internal, highlightedPositions);
  }

}


