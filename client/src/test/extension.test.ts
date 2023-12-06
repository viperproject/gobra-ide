// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as assert from 'assert';
import * as vscode from 'vscode';
import * as path from 'path';
import { State } from '../ExtensionState';
import { Commands, ContributionCommands, Helper } from '../Helper';
import { TestHelper } from './TestHelper';
import { readdir } from 'fs/promises';

const PROJECT_ROOT = path.join(__dirname, "..", "..");
const DATA_ROOT = path.join(PROJECT_ROOT, "src", "test", "data");
const ASSERT_TRUE = "assert_true.gobra";
const ASSERT_FALSE = "assert_false.gobra";
const FAILING_POST_GOBRA = "failing_post.gobra";
const FAILING_POST_GO = "failing_post.go";
const PKG_FILE_1 = "pkg/file1.gobra";

const URL_CONVERSION_TIMEOUT_MS = 5000; // 5s
const GOBRA_TOOL_UPDATE_TIMEOUT_MS = 4 * 60 * 1000; // 4min
const GOBRA_VERIFICATION_TIMEOUT_MS = 1 * 60 * 1000; // 1min

function log(msg: string) {
    console.log("[UnitTest] " + msg);
}

function getTestDataPath(fileName: string): string {
    return path.join(DATA_ROOT, fileName);
}

async function getGobraFilesInDataPath(): Promise<string[]> {
    function getExtension(filename: string): string | undefined {
        return filename.split('.').pop();
    }
    
    const filenames = await readdir(DATA_ROOT);
    return filenames
        .filter(filename => {
            const ext = getExtension(filename);
            return ext != undefined && (ext === "go" || ext === "gobra");
        })
        .map(filename => path.join(DATA_ROOT, filename));
}

async function closeAllFiles(): Promise<void> {
    log("closing all files");
    await vscode.commands.executeCommand('workbench.action.closeAllEditors');
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

async function openAndVerify(fileName: string, command: string): Promise<vscode.TextDocument> {
    await closeAllFiles();
    // open file, ...
    const document = await openFile(fileName);
    // ... send verification command to server...
    const executed = new Promise((resolve) => State.client.onNotification(Commands.overallResult, resolve));
    console.debug(`execute ${command}`);
    await vscode.commands.executeCommand(command);
    // ... and wait for result notification from server
    await executed;
    console.debug(`executed ${command}`);
    return document;
}

function openAndVerifyFile(fileName: string): Promise<vscode.TextDocument> {
    return openAndVerify(fileName, ContributionCommands.verifyFile);
}

async function openAndVerifyPackage(fileName: string): Promise<vscode.TextDocument> {
    return openAndVerify(fileName, ContributionCommands.verifyPackage);
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
        const document = await openAndVerifyFile(ASSERT_TRUE);
        const diagnostics = vscode.languages.getDiagnostics(document.uri);
        assert.strictEqual(diagnostics.length, 0);
    });

    test("Verify simple incorrect program", async function() {
        this.timeout(GOBRA_VERIFICATION_TIMEOUT_MS);
        const document = await openAndVerifyFile(ASSERT_FALSE);
        const diagnostics = vscode.languages.getDiagnostics(document.uri);
        assert.strictEqual(diagnostics.length, 1);
        assert.strictEqual(diagnostics[0].severity, vscode.DiagnosticSeverity.Error);
    });

    test("Underline the 'false' in the failing postcondition", async function() {
        this.timeout(GOBRA_VERIFICATION_TIMEOUT_MS);
        const document = await openAndVerifyFile(FAILING_POST_GOBRA);
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
        const document = await openAndVerifyFile(FAILING_POST_GO);
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

    test("Verifying basic programs as a package fails because of differing package names", async function() {
        this.timeout(GOBRA_VERIFICATION_TIMEOUT_MS);
        // note: we have to consider the diagnostics of all gobra and go files because we do not want
        // to rely on a particular behavior of Gobra. Gobra will report an error in files with 
        // differing package name. However, the file in which the error occurs depends on the order
        // in which Gobra visits the files.
        const filePaths = await getGobraFilesInDataPath();
        const fileUris = filePaths.map(path => vscode.Uri.file(path));
        const diagnosticsBeforeVerification = new Map(
            fileUris.map(fileUri => [fileUri, vscode.languages.getDiagnostics(fileUri)]));
        await openAndVerifyPackage(ASSERT_FALSE);
        const diagnosticsAfterVerification = new Map(
            fileUris.map(fileUri => [fileUri, vscode.languages.getDiagnostics(fileUri)]));
        const newDiagnostics = fileUris.flatMap(fileUri => {
            const prevDiags = diagnosticsBeforeVerification.get(fileUri);
            const curDiags = diagnosticsAfterVerification.get(fileUri);
            const newDiags = curDiags?.filter(diag => !prevDiags?.includes(diag));
            return newDiags || [];
        });
        const newErrorDiagnostics = newDiagnostics
            .filter(diag => diag.severity === vscode.DiagnosticSeverity.Error);
        assert.ok(newErrorDiagnostics.length >= 1);
    });

    test("Verifying a package consisting of two files succeeds", async function() {
        this.timeout(GOBRA_VERIFICATION_TIMEOUT_MS);
        const document = await openAndVerifyPackage(PKG_FILE_1);
        const diagnostics = vscode.languages.getDiagnostics(document.uri);
        assert.strictEqual(diagnostics.length, 0);
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
