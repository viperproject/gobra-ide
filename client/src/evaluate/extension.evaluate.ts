import * as assert from 'assert';
import { after } from 'mocha';
import { URI } from 'vscode-uri';
import * as fs from 'fs';

import * as vscode from 'vscode';


import { State } from "../ExtensionState";
import * as path from 'path';
import { Commands } from "../Helper";
import { VerifierConfig, GobraSettings, FileData } from "../MessagePayloads";
import { Helper } from "../Helper";
import { Verifier } from '../VerificationService';

// TODO add writing results to files.

suite('Evaluation Suite', () => {
  after(() => {
    vscode.window.showInformationMessage('All evaluations done!');
  });

  test('Evaluate Sequential Verification', async () => {
    var now = require("performance-now");

    /**
      * Start language server.
      */
    State.startLanguageServer(EvaluationHelper.fileSystemWatcher);
    await State.client.onReady();

    /**
      * Iterate over all files in the evaluationFiles directory
      */
    let fileArray = await vscode.workspace.fs.readDirectory(URI.file(EvaluationHelper.evaluationFilesDir));

    for (let j = 0; j < EvaluationHelper.repetitions; j++) {
      Verifier.flushCache();
      console.log("--------------------------------------");
      for (let i = 0; i < fileArray.length; i++) {
        let file = fileArray[i][0];
        if (file == "working.gobra") continue;
  
        let filePath = path.join(EvaluationHelper.evaluationFilesDir, file);
  
        // copy file to the working file
        await vscode.workspace.fs.copy(URI.file(filePath), URI.file(EvaluationHelper.workingFilePath), { overwrite: true });
  
        let startTime = now();
        await EvaluationHelper.verify(EvaluationHelper.transformToFileUri(EvaluationHelper.workingFilePath), EvaluationHelper.workingFilePath);
        let endTime = now();
  
        let elapsedTime = (endTime - startTime) / 1000.0;

        console.log("Elapsed time for " + file + ": " + elapsedTime.toFixed(3) + "s");
      }
    }

    await vscode.workspace.fs.delete(URI.file(EvaluationHelper.workingFilePath));
    


  });
});

class EvaluationHelper {
  public static repetitions = 1;

  /**
    * Paths to files used in evaluation.
    */
  public static evaluationFilesDir = path.join(__dirname.split("out")[0], "src", "evaluate", "evaluationFiles");
  public static workingFilePath = path.join(EvaluationHelper.evaluationFilesDir, "working.gobra");
  public static evaluationResultsFile = path.join(__dirname.split("out")[0], "evaluationResults.txt");

  
  public static fileSystemWatcher = vscode.workspace.createFileSystemWatcher("**/*.{gobra, go}");


  public static transformToFileUri(filePath: string): string {
    return URI.file(filePath).toString();
  }

  public static verify(fileUri: string, filePath: string): Promise<any> {
    let config = new VerifierConfig();
    config.fileData.fileUri = fileUri;
    config.fileData.filePath = filePath;

    State.client.sendNotification(Commands.setOpenFileUri, fileUri);
    State.client.sendNotification(Commands.verifyGobraFile, Helper.configToJson(config));

    return new Promise((resolve, reject) => {
      State.client.onNotification(Commands.overallResult, () => {
        resolve();
      });
    })
  }


  
}