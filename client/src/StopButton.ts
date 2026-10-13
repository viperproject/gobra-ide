// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

import * as vscode from 'vscode';
import { ContributionCommands, Texts } from './Helper.js';

/** status bar button to stop a running verification */
export class StopButton {
  public item: vscode.StatusBarItem;

  constructor(priority: number) {
    this.item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, priority);
    this.item.text = Texts.stopVerification;
    this.item.tooltip = Texts.stopVerificationTooltip;
    this.item.command = ContributionCommands.stopVerification;
    this.item.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
  }

  public setVisible(visible: boolean): void {
    if (visible) {
      this.item.show();
    } else {
      this.item.hide();
    }
  }
}
