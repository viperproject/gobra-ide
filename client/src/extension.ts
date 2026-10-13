// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as vscode from 'vscode';
import { URI } from 'vscode-uri';

import { State } from './ExtensionState.js';
import { Verifier } from './VerificationService.js';
import { FileData, VerifierConfig } from './MessagePayloads.js';
import { Helper } from './Helper.js';
import { locateGobraTools } from './GobraTools.js';
import { Location } from 'vs-verification-toolbox';

// Re-export internal modules so that tests can import them from the webpack bundle,
// ensuring tests share the same module instances as the running extension.
export { State } from './ExtensionState.js';
export { Helper, Commands, ContributionCommands } from './Helper.js';
export { Verifier } from './VerificationService.js';
export { OverallVerificationResult } from './MessagePayloads.js';


let fileSystemWatcher: vscode.FileSystemWatcher;

export function activate(context: vscode.ExtensionContext): Thenable<any> {
	// Uri of the file which triggered the plugin activation.
	const fileUri = Helper.getCurrentlyOpenFileUri();
	if (fileUri == null) {
		const msg = `getting currently open file has failed`;
		Helper.log(msg);
		return Promise.reject(new Error(msg));
	}

	async function startServer(location: Location): Promise<Location> {
		// create and start Gobra Server
		fileSystemWatcher = vscode.workspace.createFileSystemWatcher("**/*.{gobra, go}");
		await State.startLanguageServer(context, fileSystemWatcher, location);
		return location;
	}

	function initVerifier(fileUri: URI): (location: Location) => void {
		return location => {
			const fileData = new FileData(fileUri);
			const z3Path = Helper.getZ3Path(location);
			const boogiePath = Helper.getBoogiePath(location);
			if (z3Path.error != null) {
				vscode.window.showErrorMessage(z3Path.error);
				throw new Error(z3Path.error);
			}
			if (boogiePath.error != null) {
				vscode.window.showErrorMessage(boogiePath.error);
				throw new Error(boogiePath.error);
			}
			const verifierConfig = new VerifierConfig([fileData], [], z3Path.path, boogiePath.path);
			Verifier.initialize(context, verifierConfig, fileUri);
			Helper.log("The extension is now active.");
		}
	}

	// locate the gobra tools (they are bundled with the extension unless the build version 'External' is selected)
	return locateGobraTools(context)
		.then(startServer)
		.then(initVerifier(fileUri));
}

export async function deactivate(): Promise<void> {
	Helper.log("Deactivating");
	await State.disposeServer();
	Helper.log("Server is disposed");
}
