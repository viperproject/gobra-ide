import { State } from "./ExtensionState";
import { Helper, Commands } from "./Helper";
import { StatusBar } from "./StatusBar";
import * as vscode from 'vscode';
import { VerifierConfig, VerificationResult } from "./MessagePayloads";


export class Verifier {
    // insert fields defining the verifier
    public static verifyItem: StatusBar;

     public static initialize(): void {
         // Initialize Verification Button in Statusbar
         Verifier.verifyItem = new StatusBar("Hello from Gobra", 10);
         Helper.registerCommand(Commands.verifyFile, Verifier.verifyFile, State.context);
         Verifier.verifyItem.setCommand(Commands.verifyFile, State.context);

     }


    // verifies the file which is currently open in the editor
    public static verifyFile(): void {
        if (!State.verificationRunning && 
            vscode.window.activeTextEditor && 
            vscode.window.activeTextEditor.document) {
            State.toggleVerificationRunning();

            vscode.window.activeTextEditor.document.save().then((saved: boolean) => {
                console.log("sending verification request");
                let config = new VerifierConfig();
                State.client.sendRequest(Commands.verifyFile, Helper.configToJson(config)).then((jsonRes: string) => {
                    Verifier.handleResult(jsonRes);
                });
            });
            
        }
    }

    private static handleResult(jsonRes: string): void {
        let res: VerificationResult = Helper.jsonToResult(jsonRes);
        if (res.success) {
            Verifier.verifyItem.setProperties("Verification succeeded!", "lightgreen");
        } else {
            Verifier.verifyItem.setProperties("Verification failed with: " + res.error, "red");
        }


        State.toggleVerificationRunning();
    }



}


