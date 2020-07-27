import * as path from 'path';
import * as vscode from 'vscode';
import { VerifierConfig, GobraSettings, FileData } from "../MessagePayloads";
import { Commands } from "../Helper";
import { Helper } from "../Helper";
import { State } from "../ExtensionState";
import { URI } from 'vscode-uri';
import * as fs from 'fs';

export class EvaluationHelper {
  public static repetitions = 7;
  public static multirun = false;

  /**
    * Paths to files used in evaluation.
    */
  public static evaluationFilesDir = path.join(__dirname.split("out")[0], "src", "evaluate", "evaluationFiles");
  public static workingFilePath = path.join(EvaluationHelper.evaluationFilesDir, "working.gobra");
  private static evaluationResultsPath = __dirname.split("out")[0];
  public static evaluationResultsFile = path.join(EvaluationHelper.evaluationResultsPath, "evaluationResults" + ".txt");

  
  public static fileSystemWatcher = vscode.workspace.createFileSystemWatcher("**/*.{gobra, go}");


  public static transformToFileUri(filePath: string): string {
    return URI.file(filePath).toString();
  }

  public static printFileNames(fileArray: [string, vscode.FileType][], writeStream: fs.WriteStream): void {
    for (let i = 0; i < fileArray.length; i++) {
      let file = fileArray[i][0];
      writeStream.write(file + ((i < fileArray.length - 1) ? ", " : ""));
    }
    writeStream.write("\n");
  }

  public static verify(fileUri: string, filePath: string): Promise<any> {
    let config = new VerifierConfig();
    config.fileData.fileUri = fileUri;
    config.fileData.filePath = filePath;

    let settings = new EvaluationGobraSettings();
    config.gobraSettings = settings;

    State.client.sendNotification(Commands.setOpenFileUri, fileUri);
    State.client.sendNotification(Commands.verifyGobraFile, Helper.configToJson(config));

    return new Promise((resolve, reject) => {
      State.client.onNotification(Commands.overallResult, () => {
        resolve();
      });
    })
  }
}

class EvaluationGobraSettings implements GobraSettings {
  serverMode: boolean = true;
  debug: boolean = false;
  eraseGhost: boolean = false;
  unparse: boolean = false;
  printInternal: boolean = false;
  printViper: boolean = false;
  parseOnly: boolean = false;
  loglevel: string = "OFF";
  backend: string = "SILICON";
}