import * as vscode from 'vscode';
import { URI } from 'vscode-uri';
import { VerifierConfig, VerificationResult, FileData } from "./MessagePayloads";


export class Helper {
    public static registerCommand(commandId: string, command: (...args: any[]) => any, context: vscode.ExtensionContext): void {
        context.subscriptions.push(vscode.commands.registerCommand(commandId, command));
    }

    public static getFilePath(): string {
        return vscode.window.activeTextEditor.document.fileName;
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

    public static jsonToResult(json: string): VerificationResult {
        return JSON.parse(json);
    }
}

// Defines the commands used for requests
export class Commands {
    public static verifyFile = "gobraServer/verifyFile";
    public static changeFile = "gobraServer/changeFile";
}

// Defines the texts in statusbars ...
export class Texts {
    public static helloGobra = "Hello from Gobra";
    public static verificationSuccess = "Verification succeeded!";
    public static verificationFailure = "Verification failed with: ";
}

export class Color {
    public static green = "lightgreen";
    public static white = "white";
    public static red = "red";
}




