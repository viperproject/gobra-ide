import * as vscode from "vscode";


export class StatusBarButton {
    
  public item: vscode.StatusBarItem;

  constructor(text: string, priority: number) {
    this.item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, priority);
    this.item.text = text;
    this.updateItem();
  }

  public setCommand(commandId: string, context: vscode.ExtensionContext) {
    this.item.command = commandId;
    context.subscriptions.push(this.item);

    context.subscriptions.push(vscode.window.onDidChangeActiveTextEditor(this.updateItem));
    context.subscriptions.push(vscode.window.onDidChangeTextEditorSelection(this.updateItem));
    this.updateItem();
  }

  public setProperties(text: string, color: string) {
    this.item.text = text;
    this.item.color = color;
    this.updateItem();
  }

  public addHourGlass() {
    this.item.text = "\u231B" + this.item.text;
    this.updateItem();
  }

  public removeHourGlass() {
    if (this.item.text.charAt(0) == '\u231B') {
      this.item.text = this.item.text.substring(1);
      this.updateItem();
    }
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