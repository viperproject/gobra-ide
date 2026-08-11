// this file is taken from the helloworld-test-sample from https://github.com/microsoft/vscode-extension-samples

// Copyright (c) Microsoft Corporation
//
// All rights reserved. 
//
// MIT License
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation 
// files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
// modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software 
// is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
// OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS 
// BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT 
// OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

import * as fs from "node:fs";
import * as tmp from "tmp";
import * as path from 'path';
import yargs from 'yargs';
import { runTests } from '@vscode/test-electron';
import { assert } from "console";

const PROJECT_ROOT = path.join(import.meta.dirname, "..", "..");
const DATA_ROOT = path.join(PROJECT_ROOT, "src", "test", "data");

async function main() {
	const argv = await yargs(process.argv.slice(2))
		.option('configFile', {
			description: `Path (absolute or relative to ${PROJECT_ROOT}) to the config file that should be used for testing. If none is provided, the tests default to all config files in ${path.join(DATA_ROOT, "settings")}.`,
            type: 'string',
		})
        .help() // show help if `--help` is used
        .argv;

	// The folder containing the Extension Manifest package.json
	// Passed to `--extensionDevelopmentPath`
	const extensionDevelopmentPath = PROJECT_ROOT;

	// The path to the extension test script
	// Passed to --extensionTestsPath
	const extensionTestsPath = path.resolve(import.meta.dirname, 'index.js');

	// Download VS Code, unzip it and run the integration test
	console.info("Reading VS Code version...");
	const vscode_version = fs.readFileSync(path.join(DATA_ROOT, "vscode-version")).toString().trim();
	console.info(`Tests will use VS Code version '${vscode_version}'`);
	let settings_paths: string[];
	if (argv.configFile) {
		settings_paths = [path.resolve(PROJECT_ROOT, argv.configFile)];
	} else {
		console.info("Reading list of settings...");
		const settings_list = fs.readdirSync(path.join(DATA_ROOT, "settings")).sort();
		settings_paths = settings_list.map(filename => path.join(DATA_ROOT, "settings", filename));
	}
	assert(settings_paths.length > 0, "There are no settings to test");
	
	let firstIteration = true;
	for (const settings_path of settings_paths) {
		const settings_file = path.basename(settings_path);
		console.info(`Testing settings ${settings_file}`);
		const additionalSettings: Map<string, string>[] = [new Map()];
		
		for (const addSettings of additionalSettings) {
			if (!firstIteration) {
				// workaround for a weird "exit code 55" error that happens on
				// macOS when starting a new vscode instance immediately after
				// closing an old one. (by fpoli)
				await new Promise(resolve => setTimeout(resolve, 5000));
			}
			firstIteration = false;

			if (addSettings.size === 0) {
				console.info(`Testing with settings '${settings_file}' and no additional settings`);
			} else {
				console.info(`Testing with settings '${settings_file}' and additional settings ${mapToString(addSettings)}...`);
			}

			const tmpWorkspace = tmp.dirSync({ unsafeCleanup: true });
			// use a temporary directory for VSCode's user data as VSCode creates a unix domain
			// socket in there, whose path is limited to 103 characters (at least on macOS). The
			// default location within the repository can exceed this limit (e.g. for git worktrees
			// in deeply nested folders):
			const tmpUserDataDir = tmp.dirSync({ unsafeCleanup: true });
			try {
				// Prepare the workspace with the settings
				const workspace_vscode_path = path.join(tmpWorkspace.name, ".vscode");
				const workspace_settings_path = path.join(workspace_vscode_path, "settings.json");
				fs.mkdirSync(workspace_vscode_path);
				fs.copyFileSync(settings_path, workspace_settings_path);
				// modify settings file:
				addOptionsToSettingsFile(workspace_settings_path, addSettings);

				// get environment variables
				const env: NodeJS.ProcessEnv = process.env;
				
				// Run the tests in the workspace
				await runTests({
					version: vscode_version,
					extensionDevelopmentPath,
					extensionTestsPath,
					// note that passing environment variables seems to only work when invoking the tests via CLI
					extensionTestsEnv: env,
					// Disable any other extension
					launchArgs: ["--disable-extensions", `--user-data-dir=${tmpUserDataDir.name}`, tmpWorkspace.name],
				});
			} finally {
				try {
					tmpWorkspace.removeCallback();
					tmpUserDataDir.removeCallback();
				} catch (e) {
					console.warn(`cleaning temporary directory has failed with error ${e}`);
				}
			}
		}
	}
}

function addOptionsToSettingsFile(filepath: string, additionalOptions: Map<string, string>) {
	if (additionalOptions.size == 0) {
		return;	
	}

	const fileContent = fs.readFileSync(filepath).toString();
	try {
		const json = JSON.parse(fileContent);
		additionalOptions.forEach((value, key) => json[key] = value);
		const newContent = JSON.stringify(json);
		fs.writeFileSync(filepath, newContent);
	} catch(e) {
		console.error(`parsing settings ${filepath} has failed`, e);
	}
}

function mapToString<K, V>(map: Map<K, V>) {
	const entries = map.entries();
	return Array
	  .from(entries, ([k, v]) => `\n  ${k}: ${v}`)
	  .join("") + "\n";
  }

main().catch((err) => {
	console.error(`main function has ended with an error: ${err}`);
	process.exit(1);
});
