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

    let writeStream = fs.createWriteStream(EvaluationHelper.evaluationResultsFile);

    /**
      * Start language server.
      */
    State.startLanguageServer(EvaluationHelper.fileSystemWatcher);
    await State.client.onReady();

    if (fs.existsSync(EvaluationHelper.workingFilePath))
      await vscode.workspace.fs.delete(URI.file(EvaluationHelper.workingFilePath));

    /**
      * Iterate over all files in the evaluationFiles directory
      */
    let fileArray = await vscode.workspace.fs.readDirectory(URI.file(EvaluationHelper.evaluationFilesDir));

    for (let i = 0; i < fileArray.length; i++) {
      let file = fileArray[i][0];
      writeStream.write(file + ((i < fileArray.length - 1) ? ", " : ""));
    }
    writeStream.write("\n");


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

        //writeStream.write("Elapsed time for " + file + ": " + elapsedTime.toFixed(3) + "s\n");
        writeStream.write(elapsedTime.toFixed(3) + ((i < fileArray.length - 1) ? ", " : ""));
      }
      writeStream.write("\n");
    }

    await vscode.workspace.fs.delete(URI.file(EvaluationHelper.workingFilePath));
    


  });
});

class EvaluationHelper {
  public static repetitions = 10;

  public static backend = "CARBON";
  public static serverMode = true;

  /**
    * Paths to files used in evaluation.
    */
  public static evaluationFilesDir = path.join(__dirname.split("out")[0], "src", "evaluate", "evaluationFiles", (EvaluationHelper.backend == "SILICON") ? "silicon" : "carbon");
  public static workingFilePath = path.join(EvaluationHelper.evaluationFilesDir, "working.gobra");
  private static evaluationResultsPath = __dirname.split("out")[0];
  public static evaluationResultsFile =
    path.join(EvaluationHelper.evaluationResultsPath, 
      "evaluationResults" + EvaluationHelper.backend + (EvaluationHelper.serverMode ? "serverMode" : "") + ".txt");

  
  public static fileSystemWatcher = vscode.workspace.createFileSystemWatcher("**/*.{gobra, go}");


  public static transformToFileUri(filePath: string): string {
    return URI.file(filePath).toString();
  }

  public static verify(fileUri: string, filePath: string): Promise<any> {
    let config = new VerifierConfig();
    config.fileData.fileUri = fileUri;
    config.fileData.filePath = filePath;

    let settings = new EvaluationGobraSettings(this.serverMode, this.backend);
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
  serverMode: boolean;
  debug: boolean = false;
  eraseGhost: boolean = false;
  unparse: boolean = false;
  printInternal: boolean = false;
  printViper: boolean = false;
  parseOnly: boolean = false;
  loglevel: string = "OFF";
  backend: string;

  constructor(serverMode: boolean, backend: string) {
    this.serverMode = serverMode;
    this.backend = backend;
  }
}