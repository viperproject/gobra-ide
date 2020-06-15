import * as vscode from 'vscode';
import { URI } from 'vscode-uri';
import { VerifierConfig, OverallVerificationResult, FileData, GobraSettings, PlatformDependendPath, GobraDependencies } from "./MessagePayloads";


export class Helper {
  public static isWin = /^win/.test(process.platform);
    public static isLinux = /^linux/.test(process.platform);
    public static isMac = /^darwin/.test(process.platform);


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

  public static getFileUri(): string {
    return URI.file(Helper.getFilePath()).toString();
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

  public static jsonToOverallResult(json: string): OverallVerificationResult {
    return JSON.parse(json);
  }

  public static getGobraSettings(): GobraSettings {
    let gobraSettings: unknown = vscode.workspace.getConfiguration("gobraSettings");
    return <GobraSettings> gobraSettings;
  }

  public static getGobraDependencies(): GobraDependencies {
    let gobraDependencies: unknown = vscode.workspace.getConfiguration("gobraDependencies");
    return <GobraDependencies> gobraDependencies;
  }

  public static getPlatformPath(paths: PlatformDependendPath): string {
    if (Helper.isWin && paths.windows) return paths.windows;
    if (Helper.isLinux && paths.linux) return paths.linux;
    if (Helper.isMac && paths.mac) return paths.mac;

    return null;
  }

  public static getViperToolsProvider(): string {
    let gobraDependencies: unknown = vscode.workspace.getConfiguration("gobraDependencies");
    let viperToolsProvider = Helper.getGobraDependencies().viperToolsProvider;

    return Helper.getPlatformPath(viperToolsProvider);
  }

  public static extractionAddition(): string {
    return Helper.isWin ? "\\Viper\\ViperTools" : "/Viper/ViperTools"
  }

  public static getViperToolsPath(): string {
    let viperToolsPaths = Helper.getGobraDependencies().viperToolsPaths.viperToolsPath;

    return Helper.getPlatformPath(viperToolsPaths);
  }

  public static getBoogiePath(): string {
    let boogiePaths = Helper.getGobraDependencies().viperToolsPaths.boogieExecutable;
    let viperToolsPath = Helper.getViperToolsPath();

    return Helper.getPlatformPath(boogiePaths).replace("$viperTools$", viperToolsPath + Helper.extractionAddition());
  }

  public static getZ3Path(): string {
    let z3Paths = Helper.getGobraDependencies().viperToolsPaths.z3Executable;
    let viperToolsPath = Helper.getViperToolsPath();

    return Helper.getPlatformPath(z3Paths).replace("$viperTools$", viperToolsPath + Helper.extractionAddition());
  }

  public static isServerMode(): boolean {
    return vscode.workspace.getConfiguration("gobraSettings").get("serverMode");
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

  /**
    * Commands handled by Client (VSCode)
    */
  public static overallResultNotification = "gobraServer/overallResultNotification";
  public static noVerificationResult = "gobraServer/noVerificationResult";
  public static finishedVerification = "gobraServer/finishedVerification";
  public static verificationException = "gobraServer/verificationException";
  public static finishedGoifying = "gobraServer/finishedGoifying";
  public static finishedGobrafying = "gobraServer/finishedGobrafying";
}

// Defines the texts in statusbars ...
export class Texts {
  public static helloGobra = "Hello from Gobra";
  public static flushCache = "Flush Cache";
  public static updatingViperTools = "$(sync~spin) Updating Viper Tools ...";
}

export class Color {
  public static green = "lightgreen";
  public static white = "white";
  public static red = "red";
}


/**
  * Commands contributed to VS-Code.
  */
export class ContributionCommands {
  public static flushCache = "gobra.flushCache";
  public static goifyFile = "gobra.goifyFile";
  public static gobrafyFile = "gobra.gobrafyFile";
  public static verifyFile = "gobra.verifyFile";
  public static updateViperTools = "gobra.updateViperTools"
}


