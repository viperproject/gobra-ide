// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as assert from 'assert';
import * as vscode from 'vscode';
import * as path from 'path';
import { State } from '../ExtensionState';
import { Verifier } from '../VerificationService';
import { Commands, Helper } from '../Helper';
import { TestHelper } from './TestHelper';

const PROJECT_ROOT = path.join(__dirname, "..", "..");
const DATA_ROOT = path.join(PROJECT_ROOT, "src", "test", "data");
const ASSERT_TRUE = "assert_true.gobra";
const ASSERT_FALSE = "assert_false.gobra";
const FAILING_POST_GOBRA = "failing_post.gobra";
const FAILING_POST_GO = "failing_post.go";

const URL_CONVERSION_TIMEOUT_MS = 1000; // 1s
const GOBRA_TOOL_UPDATE_TIMEOUT_MS = 4 * 60 * 1000; // 4min
const GOBRA_VERIFICATION_TIMEOUT_MS = 1 * 60 * 1000; // 1min

function log(msg: string) {
    console.log("[UnitTest] " + msg);
}

function getTestDataPath(fileName: string): string {
    return path.join(DATA_ROOT, fileName);
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

    suiteSetup(async function() {
        // set timeout to a large value such that extension can be started and Gobra tools installed:
        this.timeout(GOBRA_TOOL_UPDATE_TIMEOUT_MS);
        // activate extension:
        await TestHelper.startExtension(getTestDataPath(ASSERT_TRUE));
        log("suiteSetup done");
    });

    suiteTeardown(async function() {
        log("Tear down test suite");
        await TestHelper.stopExtension();
    });
    
    test("Recognize Gobra files", async () => {
        const document = await openFile(ASSERT_TRUE);
        assert.strictEqual(document.languageId, "gobra");
    });
    
    test("Recognize Go files", async () => {
        const document = await openFile(FAILING_POST_GO);
        assert.strictEqual(document.languageId, "go");
    });

    test("Check conversion of Gobra Tool Provider URL - regular URL", async function() {
        // as there shouldn't be any network request involved, we do not need to increase the default timeout
        const url = "https://gobra-ide.s3.eu-central-1.amazonaws.com/stable/GobraToolsLinux.zip";
        const parseResult = await Helper.parseGitHubAssetURL(url);
        assert.strictEqual(parseResult.isGitHubAsset, false);
        assert.strictEqual(await parseResult.getUrl(), url);
    })

    test("Check conversion of Gobra Tool Provider URL - latest GitHub asset", async function() {
        this.timeout(URL_CONVERSION_TIMEOUT_MS);
        const url = "github.com/viperproject/gobra-ide/releases/latest?asset-name=GobraToolsLinux.zip";
        const parseResult = await Helper.parseGitHubAssetURL(url);
        // this should return the actual URL to the asset
        assert.strictEqual(parseResult.isGitHubAsset, true);
        assert.notStrictEqual(await parseResult.getUrl(), url);
    })

    test("Check conversion of Gobra Tool Provider URL - latest pre-release GitHub asset", async function() {
        this.timeout(URL_CONVERSION_TIMEOUT_MS);
        const url = "github.com/viperproject/gobra-ide/releases/latest?asset-name=GobraToolsLinux.zip&include-prereleases";
        const parseResult = await Helper.parseGitHubAssetURL(url);
        // this should return the actual URL to the asset
        assert.strictEqual(parseResult.isGitHubAsset, true);
        assert.notStrictEqual(await parseResult.getUrl(), url);
    })

    test("Check conversion of Gobra Tool Provider URL - latest tagged GitHub asset", async function() {
        this.timeout(URL_CONVERSION_TIMEOUT_MS);
        const url = "github.com/viperproject/gobra-ide/releases/tags/v1.0-alpha.2?asset-name=GobraToolsLinux.zip";
        const parseResult = await Helper.parseGitHubAssetURL(url);
        // this should return the actual URL to the asset
        assert.strictEqual(parseResult.isGitHubAsset, true);
        assert.notStrictEqual(await parseResult.getUrl(), url);
    })

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
        const document = await openAndVerify(FAILING_POST_GOBRA);
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
        const document = await openAndVerify(FAILING_POST_GO);
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
        // the following command directly invokes the update function (without going via the VSCode ecosystem).
        // this is in particular useful for debugging / reproducing / understanding an issue as the error / exception 
        // will be visible in the output and will not be swallowed by VSCode.
        // await Verifier.updateGobraTools(State.context, true);
        await vscode.commands.executeCommand("gobra.updateGobraTools");
        log("done updating Gobra tools");
    });
});
