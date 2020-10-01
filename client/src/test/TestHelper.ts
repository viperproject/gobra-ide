// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as vscode from 'vscode';
import { State } from '../ExtensionState';
import { Event, Notifier } from '../Notifier';

export class TestHelper {

  /**
   * Open a file in the IDE
   *
   * @param filePath
   */
  public static async openFile(filePath: string): Promise<vscode.TextDocument> {
    const document = await vscode.workspace.openTextDocument(filePath);
    await vscode.window.showTextDocument(document);
    return document;
  }

  public static startExtension(initialFilePath: string): Promise<void> {
    const activationPromise = Notifier.wait(Event.EndExtensionActivation);
    return TestHelper.openFile(initialFilePath)
      .then(() => activationPromise)
  }

  /** 
   * vscode-test.runTests(...) seems not to terminate on macOS unless this function is called.
   * It looks as if the extension's deactive function is not called and hence the extension is kept alive.
   */
  public static stopExtension(): Thenable<void> {
    return State.disposeServer();
  }
}
