import * as vscode from 'vscode';

import { State } from './ExtensionState';
import { Verifier } from './VerificationService';
import { VerifierConfig } from './MessagePayloads';


let fileSystemWatcher: vscode.FileSystemWatcher;

export function activate(context: vscode.ExtensionContext) {
  // creating Gobra Server
	fileSystemWatcher = vscode.workspace.createFileSystemWatcher("**/*.gobra");
	State.startLanguageServer(context, fileSystemWatcher);

	// wait for server to start completely until next steps
	State.client.onReady().then(() =>
		{
			let verifierConfig = new VerifierConfig();
			Verifier.initialize(verifierConfig);
		}
	);

}

export function deactivate(): Promise<any> {
	return new Promise((resolve, reject) => {
		console.log("Deactivating");
		State.disposeServer().then(() => {
			console.log("Disposed Server");
			resolve();
		}).catch(e => {
			console.log("Error while disposing the Server: " + e);
		});
	});
}