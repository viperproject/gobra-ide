import * as vscode from 'vscode';
import { URI } from 'vscode-uri';
import { VerifierConfig, OverallVerificationResult, FileData } from "./MessagePayloads";
import { Verifier } from "./VerificationService";
import { State } from "./ExtensionState";
import { IdeEvents } from "./IdeEvents";


export class Helper {
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

  public static fileDataToJson(fileData: FileData): string {
    return JSON.stringify(fileData);
  }

  public static jsonToOverallResult(json: string): OverallVerificationResult {
    return JSON.parse(json);
  }

  public static getGobraConfiguration(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration("gobraSettings");
  }

  public static isServerMode(): boolean {
    return vscode.workspace.getConfiguration("gobraSettings").get("serverMode");
  }

  public static setVerificationRequestTimeout(fileUri: string, timeout: number, event: IdeEvents): void {
    State.verificationRequestTimeout = setTimeout(() => {
      Verifier.verifyFile(fileUri, event);
      Helper.clearVerificationRequestTimeout();
    }, timeout);
  }

  public static clearVerificationRequestTimeout(): void {
    clearTimeout(State.verificationRequestTimeout);
    State.verificationRequestTimeout = null;
  }

  public static refreshVerificationRequestTimeout(): void {
    State.verificationRequestTimeout.refresh();
  }

}

// Defines the commands used for requests
export class Commands {
  public static verifyFile = "gobraServer/verifyFile";
  public static changeFile = "gobraServer/changeFile";
  public static overallResultNotification = "gobraServer/overallResultNotification";
  public static noVerificationResult = "gobraServer/noVerificationResult";
  public static finishedVerification = "gobraServer/finishedVerification";
  public static verificationException = "gobraServer/verificationException";
  public static flushCache = "gobraServer/flushCache";
}

// Defines the texts in statusbars ...
export class Texts {
  public static helloGobra = "Hello from Gobra";
  public static flushCache = "Flush Cache";
}

export class Color {
  public static green = "lightgreen";
  public static white = "white";
  public static red = "red";
}




