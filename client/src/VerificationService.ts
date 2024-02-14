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
import * as path from 'path';
import { VerifierConfig, OverallVerificationResult, PreviewData, FileData, IsolationData } from "./MessagePayloads";
import { IdeEvents } from "./IdeEvents";

import { Dependency, withProgressInWindow, Location, DependencyInstaller, RemoteZipExtractor, GitHubZipExtractor, LocalReference, ConfirmResult, Success } from 'vs-verification-toolbox';
import { URI } from "vscode-uri";

export class Verifier {
  public static verifyItem: ProgressBar;
  private static verifiedMemberSuccessDecoratorType: vscode.TextEditorDecorationType;
  private static verifiedMemberFailureDecoratorType: vscode.TextEditorDecorationType;

  public static initialize(context: vscode.ExtensionContext, verifierConfig: VerifierConfig, fileUri: URI): void {
    // add file data of current file to the state
    State.verifierConfig = verifierConfig;
    State.context = context;

    // Initialize Verification Button in Statusbar
    Verifier.verifyItem = new ProgressBar(Texts.helloGobra, 10);

    Verifier.verifiedMemberSuccessDecoratorType = vscode.window.createTextEditorDecorationType({backgroundColor: 'rgba(144,238,144,0.2)', isWholeLine: true});
    Verifier.verifiedMemberFailureDecoratorType = vscode.window.createTextEditorDecorationType({backgroundColor: 'rgba(238,144,144,0.2)', isWholeLine: true});

    /**
      * Register Commands for Command Palette.
      */
    Helper.registerCommand(ContributionCommands.flushCache, Verifier.flushCache, context);
    Helper.registerCommand(ContributionCommands.goifyFile, Verifier.goifyFile, context);
    Helper.registerCommand(ContributionCommands.gobrafyFile, Verifier.gobrafyFile, context);
    Helper.registerCommand(ContributionCommands.verify, Verifier.manualVerify, context);
    Helper.registerCommand(ContributionCommands.verifyFile, Verifier.manualVerifyFile, context);
    Helper.registerCommand(ContributionCommands.verifyPackage, Verifier.manualVerifyPackage, context);
    Helper.registerCommand(ContributionCommands.verifyMember, Verifier.manualVerifyMember, context);
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
      if (!Verifier.isGoOrGobraPath(document.uri.fsPath)) return;
      if (Helper.isAutoVerify()) Verifier.verify(document.uri, IdeEvents.Open);
    }));
    // save event
    State.context.subscriptions.push(vscode.workspace.onDidSaveTextDocument(document => {
      if (!Verifier.isGoOrGobraPath(document.uri.fsPath)) return;
      if (Helper.isAutoVerify()) Verifier.verify(document.uri, IdeEvents.Save);
    }));
    // filechange event
    State.context.subscriptions.push(vscode.workspace.onDidChangeTextDocument(change => {
      if (!Verifier.isGoOrGobraPath(change.document.uri.fsPath)) return;

      if (change.document.uri.toString() === PreviewUris.viper.toString() || change.document.uri.toString() === PreviewUris.internal.toString()) {
        vscode.window.showTextDocument(change.document.uri, { preview: false, preserveFocus: false }).then(() => {
          vscode.commands.executeCommand('workbench.action.closeActiveEditor').then(() => {
            State.viperPreviewProvider.setDecorations(change.document.uri);
          });
        });
        return;
      }
    }));

    // change of build version
    State.context.subscriptions.push(vscode.workspace.onDidChangeConfiguration(e => {
      if (e.affectsConfiguration("gobraSettings.buildVersion")) {
        vscode.window.showInformationMessage(Texts.changedBuildVersion)
      }
    }))


    // verify file which triggered the activation of the plugin
    if (Helper.isAutoVerify()) Verifier.verify(fileUri, IdeEvents.Open);    
  }

  /**
    * Verifies the currently opened file or package
    */
  public static manualVerify(): void {
    State.updateConfiguration();
    const fileUri = Helper.getCurrentlyOpenFileUri();
    if (fileUri == null) {
      Helper.log(`getting currently open file has failed`);
      return;
    }
    Verifier.verify(fileUri, IdeEvents.Manual);
  }

  /**
    * Verifies the currently opened file
    */
  public static manualVerifyFile(): void {
    State.updateConfiguration();
    const fileUri = Helper.getCurrentlyOpenFileUri();
    if (fileUri == null) {
      Helper.log(`getting currently open file has failed`);
      return;
    }
    Verifier.verifyFiles([fileUri], IdeEvents.Manual);
  }

  /**
    * Verifies the currently opened package
    */
   public static manualVerifyPackage(): void {
    State.updateConfiguration();
    const fileUri = Helper.getCurrentlyOpenFileUri();
    if (fileUri == null) {
      Helper.log(`getting currently open file has failed`);
      return;
    }
    const fileUris = Verifier.getFileUrisForPackage(fileUri);
    Helper.log(`verifying the following files: ${fileUris}`);
    Verifier.verifyFiles(fileUris, IdeEvents.Manual);
  }

  /** 
   * Verifies the member at the current cursor position
   */
  public static manualVerifyMember(): void {
    State.updateConfiguration();
    const fileUri = Helper.getCurrentlyOpenFileUri();
    const lineNr = Helper.getCurrentlySelectedLineNr();
    if (fileUri == null || lineNr == null) {
      Helper.log(`getting currently open file or selected line number has failed`);
      return;
    }
    const isolationData = new IsolationData(fileUri, [lineNr]);
    Verifier.verify(fileUri, IdeEvents.Manual, [isolationData]);
  }

  /**
   * Verifies the file identified by `fileUri` or the package it belongs to depending on the current settings
   */
  public static verify(fileUri: URI, event: IdeEvents, isolationData: IsolationData[] = []): void {
    let fileUris: URI[]
    if (Helper.verifyByDefaultPackage()) {
      fileUris = Verifier.getFileUrisForPackage(fileUri);
      if (fileUris.length === 0) {
        Helper.log(`${fileUri.fsPath} was resolved to zero package files - skipping verification`);
        return;
      }
    } else {
      fileUris = [fileUri];
    }
    Verifier.verifyFiles(fileUris, event, isolationData);
  }

  /**
   * Returns all file URIs (including the given one) for files belonging to the same package
   */
  private static getFileUrisForPackage(fileUri: URI): URI[] {
    // search for files in the current directory with `.go` or `.gobra` file ending
    // this could be improved by also taking package clause into account
    // sort result to make ordering deterministic which enables caching
    const parentFolderPath = path.dirname(fileUri.fsPath);
    const filePaths = fs.readdirSync(parentFolderPath, { withFileTypes: true })
      .filter(file => file.isFile())
      .map(file => path.join(parentFolderPath, file.name))
      .filter(file => Verifier.isGoOrGobraPath(file))
      .sort();
    return filePaths.map(filePath => URI.file(filePath));
  }

  private static isGoOrGobraPath(path: String): Boolean {
    return path.endsWith(".gobra") || path.endsWith(".go")
  }

  /**
    * Verifies the files with the given fileUri as one verification task.
    * Returns when the verification request has been sent to the server or added to the list of pending verification requests
    */
  public static async verifyFiles(fileUris: URI[], event: IdeEvents, isolationData: IsolationData[] = []): Promise<void> {
    State.removeVerificationRequests(fileUris);

    // return when no text editor is active
    if (!vscode.window.activeTextEditor) return;

    // return when the gobra tools are currently being updated.
    if (State.updatingGobraTools) return;
    
    // only verify if it is a gobra file or a go file where the verification was manually invoked.
    const nonGobraAndNonGoFiles = fileUris.filter(fileUri => !Verifier.isGoOrGobraPath(fileUri.fsPath));
    const hasGoFiles = fileUris.some(fileUri => fileUri.fsPath.endsWith(".go"));
    if (nonGobraAndNonGoFiles.length > 0) {
      const msg = `Gobra can only verify files with '.gobra' and '.go' endings but got '${nonGobraAndNonGoFiles.map(f => f.fsPath).join("', '")}'`
      Helper.log(msg);
      vscode.window.showInformationMessage(msg);
      return;
    }
    if (hasGoFiles && event != IdeEvents.Manual) {
      // Go files are not automatically verified and the user has to trigger this manually
      return;
    }

    // save .go and .gobra files since they might either be part of `fileUris` or get imported
    await Verifier.saveOpenGoAndGobraFiles();

    State.updateConfiguration();
    State.updateFileData(fileUris, isolationData);

    // return if one of the files is currently getting gobrafied.
    if (fileUris.some(fileUri => State.containsRunningGobrafications(fileUri))) {
      return;
    }
    
    if (!State.containsRunningVerification(fileUris)) {
      
      State.addRunningVerification(fileUris);
      fileUris.forEach(fileUri => Verifier.verifyItem.progress(fileUri, 0));

      Helper.log("sending verification request");
      State.client.sendNotification(Commands.verify, Helper.configToJson(State.verifierConfig));
    } else {
      if (!State.containsVerificationRequests(fileUris) && event != IdeEvents.Save) {
        State.addVerificationRequests(fileUris, event);
      }
    }
  }

  /**
   * Saves all open `.go` and `.gobra` files if they contain unsaved changes
   */
  private static async saveOpenGoAndGobraFiles(): Promise<void> {
    const savePromises = vscode.window.visibleTextEditors
      .filter(editor => Verifier.isGoOrGobraPath(editor.document.uri.fsPath))
      .filter(editor => editor.document.isDirty)
      .map(editor => editor.document.save().then(success => {
        if (!success) {
          throw new Error(`Saving ${editor.document.fileName} before verification failed`);
        }
      }));
    await Promise.all(savePromises);
  }

  /**
    * Verifies the files if it is in the verification requests queue.
    */
  private static reverifyFiles(fileUris: URI[]): void {
    const event = State.getVerificationRequestsEvent(fileUris);
    if (event && Helper.isAutoVerify()) {
      State.removeVerificationRequests(fileUris);
      Verifier.verifyFiles(fileUris, event);
    }
  }


  /**
    * Transform the currently open file to a Go file with the goified annotations.
    * Open the Goified file when the Goification has terminated and succeeded.
    */
  public static goifyFile(): void {
    const fileUri = Helper.getCurrentlyOpenFileUri();
    if (fileUri == null) {
      Helper.log(`getting currently open file has failed`);
      return;
    }

    State.updateFileData([fileUri], []);
    const fileData = new FileData(fileUri);

    // only goify if it is a gobra file
    if (!fileUri.fsPath.endsWith(".gobra")) {
      vscode.window.showErrorMessage("Can only Goify Gobra files!");
      return;
    } 
    

    if (!State.containsRunningGoifications(fileUri)) {
      State.addRunningGoifications(fileUri);
      
      const editor = vscode.window.activeTextEditor;
      if (editor) {
        editor.document.save().then((saved: boolean) => {
          Helper.log("sending goification request");
          State.client.sendNotification(Commands.goifyFile, Helper.fileDataToJson(fileData));
        })
      } else {
        Helper.log("saving document for goifying was not possible");
      }
    } else {
      vscode.window.showInformationMessage("There is already a Goification running for file " + fileUri);
    }
  }


  /**
    * Transform the currently open file to a Gobra file with proof annotations.
    * Open the Gobrafied file when the Gobrafication has terminated and succeeded.
    */
  public static gobrafyFile(): void {
    const fileUri = Helper.getCurrentlyOpenFileUri();
    if (fileUri == null) {
      Helper.log(`getting currently open file has failed`);
      return;
    }

    State.updateFileData([fileUri], []);
    const fileData = new FileData(fileUri);

    // only gobrafy if it is a go file
    if (!fileUri.fsPath.endsWith(".go")) {
      vscode.window.showErrorMessage("Can only Gobrafy Go files!");
      return;
    }
    
    if (!State.containsRunningGobrafications(fileUri)) {
      State.addRunningGobrafications(fileUri);

      const editor = vscode.window.activeTextEditor;
      if (editor) {
        editor.document.save().then((saved: boolean) => {
          Helper.log("sending gobrafication request");
          State.client.sendNotification(Commands.gobrafyFile, Helper.fileDataToJson(fileData));
        })
      } else {
        Helper.log("saving document for gobrafying was not possible");
      }
    } else {
      vscode.window.showInformationMessage("There is already a Gobrafication running for file " + fileUri.fsPath);
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
    const fileUri = Helper.getCurrentlyOpenFileUri();
    if (fileUri == null) {
      Helper.log(`getting currently open file has failed`);
      return;
    }
    
    // State.updateFileData([fileUri]);
    const fileData = new FileData(fileUri);
    State.client.sendNotification(Commands.changeFile, Helper.fileDataToJson(fileData));
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

    /** confirms the update and shuts down Gobra Server if it is running */
    async function confirmAndStopServer(): Promise<ConfirmResult> {
      const confirmResult = await confirm();
      await State.disposeServer();
      return confirmResult;
    }
    
    State.updatingGobraTools = true;
    const selectedChannel = Helper.getBuildChannel();
    const dependency = await this.getDependency(context);
    Helper.log(`Ensuring dependencies for build channel ${selectedChannel}`);
    const { result: installationResult, didReportProgress } = await withProgressInWindow(
      shouldUpdate ? Texts.updatingGobraTools : Texts.ensuringGobraTools,
      listener => dependency.install(selectedChannel, shouldUpdate, listener, confirmAndStopServer)
    ).catch(Helper.rethrow(`Downloading and unzipping the Gobra Tools has failed`));

    if (!(installationResult instanceof Success)) {
      throw new Error(Texts.gobraToolsInstallationDenied);
    }

    const location = installationResult.value;
    if (Helper.isLinux || Helper.isMac) {
      const z3Path = Helper.getZ3Path(location);
      const boogiePath = Helper.getBoogiePath(location);
      if (z3Path.error != null) {
        throw new Error(z3Path.error);
      }
      if (boogiePath.error != null) {
        throw new Error(boogiePath.error);
      }
      fs.chmodSync(z3Path.path, '755');
      fs.chmodSync(boogiePath.path, '755');
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
    const toolsPath = Helper.getLocalGobraToolsPath();
    // do not check here whether path actually exist because this build version might not even be used
    return new LocalReference(toolsPath.path);
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

    let fileUris: URI[];
    const fileUri = Helper.getCurrentlyOpenFileUri();
    if (fileUri == null) {
      Helper.log(`getting currently open file has failed`);
      return;
    }
    if (Helper.verifyByDefaultPackage()) {
      fileUris = Verifier.getFileUrisForPackage(fileUri);
    } else {
      fileUris = [fileUri];
    }

    State.updateFileData(fileUris, []);
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

    let fileUris: URI[];
    const fileUri = Helper.getCurrentlyOpenFileUri();
    if (fileUri == null) {
      Helper.log(`getting currently open file has failed`);
      return;
    }
    if (Helper.verifyByDefaultPackage()) {
      fileUris = Verifier.getFileUrisForPackage(fileUri);
    } else {
      fileUris = [fileUri];
    }

    State.updateFileData(fileUris, []);
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

    const fileUri = Helper.getCurrentlyOpenFileUri();
    if (fileUri == null) {
      Helper.log(`getting currently open file has failed`);
      return;
    }

    if (!State.isFileInvolvedInRunningVerification(fileUri) && Helper.isAutoVerify()) {
      Verifier.verify(fileUri, IdeEvents.Open);
    }
  }

  private static handleOverallResultNotification(jsonOverallResult: string): void {
    let overallResult: OverallVerificationResult = Helper.jsonToOverallResult(jsonOverallResult);

    const fileUris = overallResult.fileUris.map(uri => URI.parse(uri));
    State.removeRunningVerification(fileUris);

    if (overallResult.success && overallResult.members.length === 0) {
      Verifier.verifyItem.setProperties(overallResult.message, Color.green);
    } else if (overallResult.success) {
      // program has only been partially verified
      Verifier.verifyItem.setProperties(overallResult.message, Color.orange);
    } else {
      Verifier.verifyItem.setProperties(overallResult.message, Color.red);
    }

    // note that we do not have to persist any data to offer this feature because Gobra server
    // sends a overall verification result notification whenever the currently open file is changed
    const textEditor = vscode.window.activeTextEditor;
    // we check whether `overallResult.members` is set in order to be backwards compatible
    if (textEditor && textEditor.document && overallResult.members) {
      const currentMembers = overallResult.members
        .filter(member => !member.isUnknown)
        .filter(member => Helper.equal(URI.parse(member.fileUri), textEditor.document.uri));
      const successRanges = currentMembers
        .filter(member => member.success)
        .map(member => member.range);
      const failureRanges = currentMembers
        .filter(member => !member.success)
        .map(member => member.range);
      textEditor.setDecorations(Verifier.verifiedMemberSuccessDecoratorType, successRanges);
      textEditor.setDecorations(Verifier.verifiedMemberFailureDecoratorType, failureRanges);
    }

    Verifier.reverifyFiles(fileUris);
  }

  private static handleVerificationProgressNotification(fileUriString: string, progress: number): void {
    Helper.log(`progress ${fileUriString}: ${progress}`);
    const fileUri = URI.parse(fileUriString);
    Verifier.verifyItem.progress(fileUri, progress);
  }


  private static handleVerificationExceptionNotification(encodedFileUris: string): void {
    const fileUriStrings = JSON.parse(encodedFileUris) as string[];
    Helper.log(`handleVerificationExceptionNotification: ${fileUriStrings}`);
    const fileUris = fileUriStrings.map(uri => URI.parse(uri));
    State.removeRunningVerification(fileUris);

    Verifier.verifyItem.setProperties(Texts.helloGobra, Color.white);
    
    Verifier.reverifyFiles(fileUris);
  }

  


  private static handleFinishedGoifyingNotification(fileUriString: string, success: boolean): void {
    const origFileUri = URI.parse(fileUriString);
    State.removeRunningGoifications(origFileUri);
    const goFileUri = URI.parse(fileUriString + ".go");

    if (success) {
      vscode.window.showTextDocument(goFileUri);
    } else {
      vscode.window.showErrorMessage("An error occured during the Goification of " + goFileUri.fsPath);
    }
  }

  private static handleFinishedGobrafyingNotification(oldFilePath: string, newFilePath: string, success: boolean): void {
    const oldFileUri = vscode.Uri.file(oldFilePath);
    const newFileUri = vscode.Uri.file(newFilePath);
    State.removeRunningGobrafications(oldFileUri);

    if (success) {
      vscode.window.showTextDocument(newFileUri).then(editor => {
        if (Helper.isAutoVerify()) Verifier.verify(newFileUri, IdeEvents.Open);
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


