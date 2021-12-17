import * as vscode from 'vscode';

export function activate(context: vscode.ExtensionContext): Thenable<any> {
    vscode.window.showInformationMessage("Hello world");
    return Promise.resolve();
}
