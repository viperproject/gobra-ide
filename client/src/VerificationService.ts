import { State } from "./ExtensionState";
import { Helper, Commands, Texts, Color } from "./Helper";
import { StatusBar } from "./StatusBar";
import * as vscode from 'vscode';
import { VerifierConfig, VerificationResult, FileData } from "./MessagePayloads";


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

     }

    // verifies the file which is currently open in the editor
    public static verifyFile(): void {
        if (!State.verificationRunning && 
            vscode.window.activeTextEditor && 
            vscode.window.activeTextEditor.document) {
            State.toggleVerificationRunning();
            State.updateConfiguration();
            Verifier.verifyItem.addHourGlass();

//            console.log(Helper.configToJson(State.verifierConfig));

            vscode.window.activeTextEditor.document.save().then((saved: boolean) => {
                console.log("sending verification request");
                State.client.sendRequest(Commands.verifyFile, Helper.configToJson(State.verifierConfig)).then((jsonRes: string) => {
                    Verifier.handleResult(jsonRes);
                });
            });
            
        }
    }

    private static handleResult(jsonRes: string): void {
        let res: VerificationResult = Helper.jsonToResult(jsonRes);
        if (res.success) {
            Verifier.verifyItem.setProperties(Texts.verificationSuccess, Color.green);
        } else {
            Verifier.verifyItem.setProperties(Texts.verificationFailure + res.error, Color.red);
        }


        State.toggleVerificationRunning();
    }

    public static changeFile(): void {
        State.client.sendNotification(Commands.changeFile, Helper.fileDataToJson(State.verifierConfig.fileData));
        // setting filedata to currently open filedata
        State.updateFileData();
        // reset status bar item
        Verifier.verifyItem.setProperties(Texts.helloGobra, Color.white);
    }



}


