// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as vscode from 'vscode';
import { URI, Utils } from 'vscode-uri';
import * as fs from 'fs';

import { State } from './ExtensionState';
import { Verifier } from './VerificationService';
import { FileData, VerifierConfig } from './MessagePayloads';
import { Helper } from './Helper';
import * as Notifier from './Notifier';
import { Location } from 'vs-verification-toolbox';


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
			Notifier.notifyExtensionActivation();
		}
	}

	// start of in a clean state by wiping Gobra Tools if this was requested via
	// environment variables. In particular, this is used for the extension tests.
	if (Helper.cleanInstall()) {
		const packageJson = context.extension.packageJSON;
		const packageId = `${packageJson.publisher}.${packageJson.name}`;
		const gobraToolsPath = Utils.joinPath(context.globalStorageUri, packageId).fsPath;
		if (fs.existsSync(gobraToolsPath)) {
			Helper.log(`cleanInstall has been requested and gobra tools already exist --> delete them`);
			// wipe gobraToolsPath if it exists:
			fs.rmdirSync(gobraToolsPath, { recursive: true });
		} else {
			Helper.log(`cleanInstall has been requested but gobra tools do not exist yet --> NOP`);
		}
	}

	// install gobra tools
	return Verifier.updateGobraTools(context, false)
		.then(startServer)
		.then(initVerifier(fileUri));
}

export async function deactivate(): Promise<void> {
	Helper.log("Deactivating");
	await State.disposeServer();
	Helper.log("Server is disposed");
}
