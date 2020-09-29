// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as assert from 'assert';
import * as vscode from 'vscode';
import * as path from 'path';
import { State } from '../ExtensionState';
import { Commands } from '../Helper';
import { TestHelper } from './TestHelper';

const PROJECT_ROOT = path.join(__dirname, "../../");
const DATA_ROOT = path.join(PROJECT_ROOT, "src", "test", "data");
const ASSERT_TRUE = "assert_true.gobra";
const ASSERT_FALSE = "assert_false.gobra";

const GOBRA_TOOL_UPDATE_TIMEOUT_MS = 2 * 60 * 1000;

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

    suiteSetup(function() {
        // set timeout to a large value such that extension can be started and Gobra tools installed:
        this.timeout(GOBRA_TOOL_UPDATE_TIMEOUT_MS);
        // activate extension:
        return TestHelper.startExtension(getTestDataPath(ASSERT_TRUE))
            .then(() => log("suiteSetup done"));
    });
    
    test("Recognize Gobra files", async () => {
        const document = await openFile(ASSERT_TRUE);
        assert.equal(document.languageId, "gobra");
    });
    
    test("Recognize Go files", async () => {
        const document = await openFile("failing_post.go");
        assert.equal(document.languageId, "go");
    });

    test("Verify simple correct program", async () => {
        const document = await openAndVerify(ASSERT_TRUE);
        const diagnostics = vscode.languages.getDiagnostics(document.uri);
        assert.equal(diagnostics.length, 0);
    });

    test("Verify simple incorrect program", async () => {
        const document = await openAndVerify(ASSERT_FALSE);
        const diagnostics = vscode.languages.getDiagnostics(document.uri);
        assert.equal(diagnostics.length, 1);
        assert.equal(diagnostics[0].severity, vscode.DiagnosticSeverity.Error);
    });

    test("Underline the 'false' in the failing postcondition", async () => {
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
    
    test("Underline the 'false' in the failing postcondition of a go program", async () => {
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
    
    test("Update Gobra tools", function() {
        // execute this test as the last one as the IDE has to be restarted afterwards
        this.timeout(GOBRA_TOOL_UPDATE_TIMEOUT_MS);
        log("start updating Gobra tools");
        return vscode.commands.executeCommand("gobra.updateGobraTools")
            .then(() => log("done updating Gobra tools"));
    });

    suiteTeardown(function() {
        return TestHelper.stopExtension()
            .then(() => log(`the extension was stopped successfully`));
    });
});
