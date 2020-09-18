// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as vscode from "vscode";
import { Texts, Color } from "./Helper";


export class ProgressBar {
    
  public item: vscode.StatusBarItem;

  constructor(text: string, priority: number, color?: string) {
    this.item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, priority);
    this.item.text = text;
    if (color) this.item.color = color;
    this.updateItem();
  }

  public setProperties(text: string, color: string) {
    this.item.text = text;
    this.item.color = color;
    this.updateItem();
  }

  public progress(fileName: string, progress: number) {
    let clampedProgress = Math.min(100, Math.max(0, progress))
    let completed = Math.floor(clampedProgress / 10);
    let progressDisplay = " " + clampedProgress + "% " + "⚫".repeat(completed) + "⚪".repeat(10 - completed);

    this.item.text = Texts.runningVerification + fileName + progressDisplay;
    this.item.color = Color.white;
    this.updateItem();
  }

  public updateItem() {
    if (vscode.window.activeTextEditor &&
        vscode.window.activeTextEditor.document &&
        (vscode.window.activeTextEditor.document.languageId == "gobra" ||
         vscode.window.activeTextEditor.document.languageId == "go")) {
          this.item.show();
    } else {
      this.item.hide();
    }
  }
}