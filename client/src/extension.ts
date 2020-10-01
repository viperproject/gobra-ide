// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as vscode from 'vscode';

import { State } from './ExtensionState';
import { Verifier } from './VerificationService';
import { VerifierConfig } from './MessagePayloads';
import { Helper } from './Helper';
import { Notifier, Event } from './Notifier';


let fileSystemWatcher: vscode.FileSystemWatcher;

export function activate(context: vscode.ExtensionContext): Thenable<any> {

	function startServer(): Promise<void> {
		// create and start Gobra Server
		fileSystemWatcher = vscode.workspace.createFileSystemWatcher("**/*.{gobra, go}");
		return State.startLanguageServer(context, fileSystemWatcher);
	}

	function initVerifier(): void {
		let verifierConfig = new VerifierConfig();
		Verifier.initialize(context, verifierConfig, fileUri);
		Notifier.notify(Event.EndExtensionActivation);
	}

	// Uri of the file which triggered the plugin activation.
	let fileUri: string = Helper.getFileUri();

	// install gobra tools
	return Verifier.updateGobraTools(false)
		.then(startServer)
		.then(initVerifier);
}

export function deactivate(): Thenable<void> | undefined {
	console.log("Deactivating");
	return State.disposeServer()
		.then(() => console.log("Server is disposed"));
}
