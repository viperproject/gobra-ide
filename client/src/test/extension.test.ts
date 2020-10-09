// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as assert from 'assert';
import * as vscode from 'vscode';
import * as path from 'path';
import { State } from '../ExtensionState';
import { Commands, Helper } from '../Helper';
import { TestHelper } from './TestHelper';
import { PathSettings } from '../MessagePayloads';

const PROJECT_ROOT = path.join(__dirname, "../../");
const DATA_ROOT = path.join(PROJECT_ROOT, "src", "test", "data");
const ASSERT_TRUE = "assert_true.gobra";
const ASSERT_FALSE = "assert_false.gobra";

const URL_CONVERSION_TIMEOUT_MS = 500;
const GOBRA_TOOL_UPDATE_TIMEOUT_MS = 4 * 60 * 1000; // 4min
const GOBRA_VERIFICATION_TIMEOUT_MS = 1 * 60 * 1000; // 1min

function log(msg: string) {
    console.log("[UnitTest] " + msg);
}

function getTestDataPath(fileName: string): string {
    return path.join(DATA_ROOT, fileName);
}

const gobraToolsPathsSection = "gobraDependencies.gobraToolsPaths";
function getServerJarPath(): string {
    const config = vscode.workspace.getConfiguration();
    const toolsPathsConfig = config.get(gobraToolsPathsSection) as PathSettings;
    if (Helper.isWin) {
        return toolsPathsConfig.serverJar.windows;
    } else if (Helper.isLinux) {
        return toolsPathsConfig.serverJar.linux;
    } else if (Helper.isMac) {
        return toolsPathsConfig.serverJar.mac;
    } else {
        return null;
    }
}

function setServerJarPath(path: string): Thenable<void> {
    const config = vscode.workspace.getConfiguration();
    const toolsPathsConfig = config.get(gobraToolsPathsSection) as PathSettings;
    if (Helper.isWin) {
        toolsPathsConfig.serverJar.windows = path;
    } else if (Helper.isLinux) {
        toolsPathsConfig.serverJar.linux = path;
    } else if (Helper.isMac) {
        toolsPathsConfig.serverJar.mac = path;
    } else {
        return Promise.reject("unkown platform");
    }
    
    return config.update(
        gobraToolsPathsSection,
        toolsPathsConfig,
        vscode.ConfigurationTarget.Global);
}

/**
 * Open a file in the IDE
 *
 * @param fileName
 */
async function openFile(fileName: string): Promise<vscode.TextDocument> {
    const filePath = getTestDataPath(fileName);
    log("Open " + filePath);
    return TestHelper.openFile(filePath);
}

async function openAndVerify(fileName: string): Promise<vscode.TextDocument> {
    // open file, ...
    const document = await openFile(fileName);
    // ... send verification command to server...
    await vscode.commands.executeCommand("gobra.verifyFile");
    // ... and wait for result notification from server
    await new Promise((resolve) => State.client.onNotification(Commands.overallResult, resolve));
    return document;
}

suite("Extension", () => {

    let previousServerJarPath: string;

    suiteSetup(async function() {
        // set timeout to a large value such that extension can be started and Gobra tools installed:
        this.timeout(GOBRA_TOOL_UPDATE_TIMEOUT_MS);
        // check whether a path to the gobra tools has been manually provided and if yes, set it as extension settings:
        const gobraServerJarPath = process.env["gobra_server_jar_path"];
        if (gobraServerJarPath) {
            previousServerJarPath = getServerJarPath();
            await setServerJarPath(gobraServerJarPath)
            log(`successfully set gobra server binary settings to ${gobraServerJarPath}`);
        }
        // activate extension:
        await TestHelper.startExtension(getTestDataPath(ASSERT_TRUE));
        log("suiteSetup done");
    });
    
    test("Recognize Gobra files", async () => {
        const document = await openFile(ASSERT_TRUE);
        assert.strictEqual(document.languageId, "gobra");
    });
    
    test("Recognize Go files", async () => {
        const document = await openFile("failing_post.go");
        assert.strictEqual(document.languageId, "go");
    });

    test("Check conversion of Gobra Tool Provider URL - regular URL", async function() {
        this.timeout(URL_CONVERSION_TIMEOUT_MS);
        const url = "https://gobra-ide.s3.eu-central-1.amazonaws.com/stable/GobraToolsLinux.zip";
        const conversionResult = await Helper.tryConvertGitHubAssetURLs(url);
        assert.strictEqual(conversionResult.converted, false);
        assert.strictEqual(conversionResult.url, url);
    })

    test("Check conversion of Gobra Tool Provider URL - latest GitHub asset", async function() {
        this.timeout(URL_CONVERSION_TIMEOUT_MS);
        const url = "github.com/viperproject/gobra-ide/releases/latest?asset-name=GobraToolsLinux.zip";
        const conversionResult = await Helper.tryConvertGitHubAssetURLs(url);
        // this should return the actual URL to the asset
        assert.strictEqual(conversionResult.converted, true);
        assert.notStrictEqual(conversionResult.url, url);
    })

    test("Check conversion of Gobra Tool Provider URL - latest pre-release GitHub asset", async function() {
        this.timeout(URL_CONVERSION_TIMEOUT_MS);
        const url = "github.com/viperproject/gobra-ide/releases/latest?asset-name=GobraToolsLinux.zip&include-prereleases";
        const conversionResult = await Helper.tryConvertGitHubAssetURLs(url);
        // this should return the actual URL to the asset
        assert.strictEqual(conversionResult.converted, true);
        assert.notStrictEqual(conversionResult.url, url);
    })

    /*
    this currently cannot be tested as there is no tagged non-nightly release yet
    test("Check conversion of Gobra Tool Provider URL - latest tagged GitHub asset", async function() {
        this.timeout(URL_CONVERSION_TIMEOUT_MS);
        const url = "github.com/viperproject/gobra-ide/releases/tags/v1?asset-name=GobraToolsLinux.zip";
        const conversionResult = await Helper.tryConvertGitHubAssetURLs(url);
        // this should return the actual URL to the asset
        assert.strictEqual(conversionResult.converted, true);
        assert.notStrictEqual(conversionResult.url, url);
    })
    */

    test("Verify simple correct program", async function() {
        this.timeout(GOBRA_VERIFICATION_TIMEOUT_MS);
        const document = await openAndVerify(ASSERT_TRUE);
        const diagnostics = vscode.languages.getDiagnostics(document.uri);
        assert.strictEqual(diagnostics.length, 0);
    });

    test("Verify simple incorrect program", async function() {
        this.timeout(GOBRA_VERIFICATION_TIMEOUT_MS);
        const document = await openAndVerify(ASSERT_FALSE);
        const diagnostics = vscode.languages.getDiagnostics(document.uri);
        assert.strictEqual(diagnostics.length, 1);
        assert.strictEqual(diagnostics[0].severity, vscode.DiagnosticSeverity.Error);
    });

    test("Underline the 'false' in the failing postcondition", async function() {
        this.timeout(GOBRA_VERIFICATION_TIMEOUT_MS);
        const document = await openAndVerify("failing_post.gobra");
        const diagnostics = vscode.languages.getDiagnostics(document.uri);
        assert.ok(
            diagnostics.some(
                (diagnostic) => (
                    document.getText(diagnostic.range).includes("false")
                )
            ),
            "The 'false' expression in the postcondition was not reported."
        );
    });
    
    test("Underline the 'false' in the failing postcondition of a go program", async function() {
        this.timeout(GOBRA_VERIFICATION_TIMEOUT_MS);
        const document = await openAndVerify("failing_post.go");
        const diagnostics = vscode.languages.getDiagnostics(document.uri);
        assert.ok(
            diagnostics.some(
                (diagnostic) => (
                    document.getText(diagnostic.range).includes("false")
                )
            ),
            "The 'false' expression in the postcondition of a go program was not reported."
        );
    });
    
    test("Update Gobra tools", async function() {
        // execute this test as the last one as the IDE has to be restarted afterwards
        this.timeout(GOBRA_TOOL_UPDATE_TIMEOUT_MS);
        log("start updating Gobra tools");
        await vscode.commands.executeCommand("gobra.updateGobraTools")
        log("done updating Gobra tools");
    });

    suiteTeardown(async function() {
        // restore gobra tools path in case we have changed the settings for running these tests:
        if (previousServerJarPath) {
            await setServerJarPath(previousServerJarPath);
            log(`successfully restored gobra server binary settings to ${previousServerJarPath}`);
        }
        await TestHelper.stopExtension();
        log(`the extension was stopped successfully`);
    });
});
