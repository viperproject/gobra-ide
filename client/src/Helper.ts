import * as vscode from 'vscode';
import { URI } from 'vscode-uri';
import { VerifierConfig, OverallVerificationResult, FileData, GobraSettings, PlatformDependendPath, GobraDependencies, PreviewData, HighlightingPosition } from "./MessagePayloads";


export class Helper {
  public static isWin = /^win/.test(process.platform);
  public static isLinux = /^linux/.test(process.platform);
  public static isMac = /^darwin/.test(process.platform);

  public static isServerMode(): boolean {
    return vscode.workspace.getConfiguration("gobraSettings").get("serverMode");
  }

  public static isNightly(): boolean {
    return vscode.workspace.getConfiguration("gobraSettings").get("buildVersion") == "nightly";
  }


  public static registerCommand(commandId: string, command: (...args: any[]) => any, context: vscode.ExtensionContext): void {
    context.subscriptions.push(vscode.commands.registerCommand(commandId, command));
  }

  public static getFilePath(): string {
    if (vscode.window.activeTextEditor && vscode.window.activeTextEditor.document) {
      return vscode.window.activeTextEditor.document.fileName;
    } else {
      return "";
    }
  }

  public static getFileName(path: string): string {
    return path.split('/').pop();
  }

  public static getFileUri(): string {
    return URI.file(Helper.getFilePath()).toString();
  }

  public static getSelections(): vscode.Range[] {
    return vscode.window.activeTextEditor.selections.map(s => new vscode.Range(s.start, s.end));
  }

  public static configToJson(config: VerifierConfig): string {
    return JSON.stringify(config);
  }

  public static gobraSettingsToJson(settings: GobraSettings): string {
    return JSON.stringify(settings);
  }

  public static fileDataToJson(fileData: FileData): string {
    return JSON.stringify(fileData);
  }

  public static previewDataToJson(previewData: PreviewData): string {
    return JSON.stringify(previewData);
  }

  public static jsonToOverallResult(json: string): OverallVerificationResult {
    return JSON.parse(json);
  }

  public static jsonToHighlightingPositions(json: string): HighlightingPosition[] {
    return JSON.parse(json);
  }

  public static getGobraSettings(): GobraSettings {
    let gobraSettings: unknown = vscode.workspace.getConfiguration("gobraSettings");
    return <GobraSettings> gobraSettings;
  }


  /**
    * Helper functions to get Paths of the dependencies.
    */
  private static getGobraDependencies(): GobraDependencies {
    let gobraDependencies: unknown = vscode.workspace.getConfiguration("gobraDependencies");
    return <GobraDependencies> gobraDependencies;
  }

  private static getPlatformPath(paths: PlatformDependendPath): string {
    if (Helper.isWin && paths.windows) return paths.windows;
    if (Helper.isLinux && paths.linux) return paths.linux;
    if (Helper.isMac && paths.mac) return paths.mac;
    return null;
  }

  /**
    * Specifies the Path added by the zip extractor.
    */
  private static extractionAddition(): string {
    return Helper.isWin ? "\\Gobra\\GobraTools" : "/Gobra/GobraTools"
  }

  // TODO: change paths of providers to actual zips when they exist and also use server from this zip
  
  /**
    * Get URL of repository where Gobra Tools are hosted.
    */
  public static getGobraToolsProvider(nightly: boolean = false): string {
    let gobraToolsProvider = Helper.getGobraDependencies().gobraToolsProvider;
    return Helper.getPlatformPath(nightly ? gobraToolsProvider.nightly : gobraToolsProvider.stable);
  }

  /**
    * Get Location where Gobra Tools will be installed.
    */
  public static getGobraToolsPath(): string {
    let gobraToolsPaths = Helper.getGobraDependencies().gobraToolsPaths.gobraToolsPath;
    return Helper.getPlatformPath(gobraToolsPaths);
  }

  public static getServerJarPath(nightly: boolean = false): string {
    let serverJarPaths = Helper.getGobraDependencies().gobraToolsPaths.serverJar;
    return Helper.getPlatformPath(serverJarPaths).replace("$gobraTools$", Helper.getGobraToolsPath() + Helper.extractionAddition());
  }

  
  public static getBoogiePath(nightly: boolean = false): string {
    let boogiePaths = Helper.getGobraDependencies().gobraToolsPaths.boogieExecutable;
    return Helper.getPlatformPath(boogiePaths).replace("$gobraTools$", Helper.getGobraToolsPath() + Helper.extractionAddition());
  }

  public static getZ3Path(nightly: boolean = false): string {
    let z3Paths = Helper.getGobraDependencies().gobraToolsPaths.z3Executable;
    return Helper.getPlatformPath(z3Paths).replace("$gobraTools$", Helper.getGobraToolsPath() + Helper.extractionAddition());
  }


  /**
    * Function to delay for 200ms to resolve weird bugs with switching of tabs.
    */
  public static delay() {
    return new Promise((resolve, reject) => setTimeout(resolve, 400));
  }

}


export class Commands {
  /**
    * Commands handled by Gobra-Server
    */
  public static verifyGobraFile = "gobraServer/verifyGobraFile";
  public static verifyGoFile = "gobraServer/verifyGoFile";
  public static changeFile = "gobraServer/changeFile";
  public static flushCache = "gobraServer/flushCache";
  public static goifyFile = "gobraServer/goifyFile";
  public static gobrafyFile = "gobraServer/gobrafyFile";
  public static setOpenFileUri = "gobraServer/setOpenFileUri";
  public static codePreview = "gobraServer/codePreview";

  /**
    * Commands handled by Client (VSCode)
    */
  public static overallResult = "gobraServer/overallResult";
  public static noVerificationInformation = "gobraServer/noVerificationInformation";
  public static verificationProgress = "gobraServer/verificationProgress";
  public static finishedVerification = "gobraServer/finishedVerification";
  public static verificationException = "gobraServer/verificationException";
  public static finishedGoifying = "gobraServer/finishedGoifying";
  public static finishedGobrafying = "gobraServer/finishedGobrafying";
  public static finishedViperCodePreview = "gobraServer/finishedViperCodePreview";
  public static finishedInternalCodePreview = "gobraServer/finishedInternalCodePreview";
}

// Defines the texts in statusbars ...
export class Texts {
  public static runningVerification = "Verification of ";
  public static helloGobra = "Hello from Gobra";
  public static flushCache = "Flush Cache";
  public static updatingGobraTools = "Updating Gobra Tools";
  public static installingGobraTools = "Installing Gobra Tools";
  public static successfulUpdatingGobraTools = "Successfully updated Gobra Tools. Please restart the IDE.";
  public static successfulInstallingGobraTools = "Successfully installed Gobra Tools.";
  public static changedBuildVersion = "Changed the build version of Gobra Tools. Please restart the IDE.";
}

export class Color {
  public static green = "lightgreen";
  public static white = "white";
  public static red = "red";
  public static orange = "orange";
  public static darkgreen = "green";
}


/**
  * Commands contributed to VS-Code.
  */
export class ContributionCommands {
  public static flushCache = "gobra.flushCache";
  public static goifyFile = "gobra.goifyFile";
  public static gobrafyFile = "gobra.gobrafyFile";
  public static verifyFile = "gobra.verifyFile";
  public static updateGobraTools = "gobra.updateGobraTools";
  public static showViperCodePreview = "gobra.showViperCodePreview";
  public static showInternalCodePreview = "gobra.showInternalCodePreview";
}


/**
  * File schemes used for displaying the preview of the translations.
  */
export class FileSchemes {
  public static viper = "viperPreview";
  public static internal = "internalPreview";
}

/**
  * Uris for the files which are used for the preview of code.
  */
export class PreviewUris {
  public static viper = vscode.Uri.parse(FileSchemes.viper + ":viperPreview/");
  public static internal = vscode.Uri.parse(FileSchemes.internal + ":internalPreview/");
}


