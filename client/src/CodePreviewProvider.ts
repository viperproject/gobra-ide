import * as vscode from 'vscode';
import { HighlightingPosition } from './MessagePayloads';

export class CodePreviewProvider implements vscode.TextDocumentContentProvider {
  private static decoration = vscode.window.createTextEditorDecorationType({
    backgroundColor: 'green'
  });

  codePreview = "";

  onDidChangeEmitter = new vscode.EventEmitter<vscode.Uri>();
  onDidChange = this.onDidChangeEmitter.event;

  updateCodePreview(uri: vscode.Uri, codePreview: string, highlightedPositions: HighlightingPosition[]) {
    this.codePreview = codePreview;
    this.onDidChangeEmitter.fire(uri);

    vscode.window.showTextDocument(uri, { preview: false, viewColumn: vscode.ViewColumn.Beside }).then(editor => {
      console.log("showed text document");

        
      let decorationRanges = highlightedPositions.map(pos =>
        new vscode.Range(
          editor.document.positionAt(pos.startIndex),
          editor.document.positionAt(pos.startIndex + pos.length)
        ));

      editor.setDecorations(CodePreviewProvider.decoration, decorationRanges);
        
    });
  }

  provideTextDocumentContent(uri: vscode.Uri): string {
    return this.codePreview;
  }
}