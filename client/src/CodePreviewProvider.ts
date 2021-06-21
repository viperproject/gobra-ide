// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as vscode from 'vscode';
import { HighlightingPosition } from './MessagePayloads';

export class CodePreviewProvider implements vscode.TextDocumentContentProvider {
  private static decoration = vscode.window.createTextEditorDecorationType({
    isWholeLine: true,
    backgroundColor: 'green'
  });

  codePreview = "";
  highlightedPositions: HighlightingPosition[] = [];

  onDidChangeEmitter = new vscode.EventEmitter<vscode.Uri>();
  onDidChange = this.onDidChangeEmitter.event;


  setDecorations(uri: vscode.Uri): void {
    vscode.window.showTextDocument(uri, { preview: false, viewColumn: vscode.ViewColumn.Two }).then(editor => {
      let decorationRanges = this.highlightedPositions.map(pos => {
        let line = editor.document.positionAt(pos.startIndex).line;
        return new vscode.Range(
          new vscode.Position(line, 0),
          new vscode.Position(line, 1)
        )});

      let firstHighlighting = decorationRanges.map(r => r.start.line).sort((a, b) => a - b)[0];
      var cursorPosition = editor.selection.active.with(firstHighlighting, 0);
      editor.revealRange(new vscode.Range(cursorPosition, cursorPosition), vscode.TextEditorRevealType.AtTop);

      editor.setDecorations(CodePreviewProvider.decoration, decorationRanges);
        
    });
  }

  async updateCodePreview(uri: vscode.Uri, codePreview: string, highlightedPositions: HighlightingPosition[]) {
    this.codePreview = codePreview;
    this.highlightedPositions = highlightedPositions;
    this.onDidChangeEmitter.fire(uri);

    
    this.setDecorations(uri);
  }

  provideTextDocumentContent(uri: vscode.Uri): string {
    return this.codePreview;
  }
}