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
    await TestHelper.openFile(initialFilePath);
    // Dynamically import the webpack bundle to access the shared Notifier instance
    const { Notifier } = await import('../extension.js');
    await Notifier.waitExtensionActivation();
  }

  /**
   * vscode-test.runTests(...) seems not to terminate on macOS unless this function is called.
   * It looks as if the extension's deactive function is not called and hence the extension is kept alive.
   */
  public static async stopExtension(): Promise<void> {
    const extension = await import('../extension.js');
    await extension.deactivate();
  }
}
