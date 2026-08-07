// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import { createRequire } from 'node:module';
import type { TextDocument } from 'vscode';
const require = createRequire(import.meta.url);
const vscode = require('vscode') as typeof import('vscode');

export class TestHelper {

  /**
   * Open a file in the IDE
   *
   * @param filePath
   */
  public static async openFile(filePath: string): Promise<TextDocument> {
    const document = await vscode.workspace.openTextDocument(filePath);
    await vscode.window.showTextDocument(document);
    return document;
  }

  public static async startExtension(initialFilePath: string): Promise<void> {
    // opening the file triggers the extension's activation (via its activation events):
    await TestHelper.openFile(initialFilePath);
    // derive the extension identifier from the extension's manifest (note that this file is
    // executed as `dist/test/TestHelper.js`, i.e., the manifest is located two levels up):
    const packageJson = require('../../package.json') as { publisher: string, name: string };
    const packageId = `${packageJson.publisher}.${packageJson.name}`;
    const extension = vscode.extensions.getExtension(packageId);
    if (extension == null) {
      throw new Error(`extension '${packageId}' was not found`);
    }
    // `activate` returns the promise of the (possibly already in-flight) activation, i.e., it
    // resolves as soon as the extension's activation has completed and rejects if the activation
    // has failed:
    await extension.activate();
  }

  /**
   * vscode-test.runTests(...) seems not to terminate on macOS unless this function is called.
   * It looks as if the extension's deactive function is not called and hence the extension is kept alive.
   */
  public static async stopExtension(): Promise<void> {
    // dynamically import the webpack bundle such that `deactivate` is called on the very module
    // instance that VSCode has activated:
    const extension = await import('../extension.js');
    await extension.deactivate();
  }
}
