import * as vscode from 'vscode';
import { HighlightingPosition } from './MessagePayloads';
import { Helper } from './Helper';

export class CodePreviewProvider implements vscode.TextDocumentContentProvider {
  private static decoration = vscode.window.createTextEditorDecorationType({
    backgroundColor: 'green'
  });

  codePreview = "";
  highlightedPositions = [];

  onDidChangeEmitter = new vscode.EventEmitter<vscode.Uri>();
  onDidChange = this.onDidChangeEmitter.event;


  setDecorations(uri: vscode.Uri): void {
    vscode.window.showTextDocument(uri, { preview: false, viewColumn: vscode.ViewColumn.Two }).then(editor => {
      let decorationRanges = this.highlightedPositions.map(pos => {
        let line = editor.document.positionAt(pos.startIndex).line;
        return new vscode.Range(
          new vscode.Position(line, 0),
          new vscode.Position(line, 1000)
        )});

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