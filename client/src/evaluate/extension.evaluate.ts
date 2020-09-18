// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as assert from 'assert';
import { after } from 'mocha';
import { URI } from 'vscode-uri';
import * as fs from 'fs';
import * as vscode from 'vscode';
import { State } from "../ExtensionState";
import * as path from 'path';

import { EvaluationHelper } from "./EvaluationHelper";

import { Verifier } from '../VerificationService';

suite('Evaluation Suite', () => {
  after(() => {
    vscode.window.showInformationMessage('All evaluations done!');
  });

  test('Evaluate Multiple Verification', async () => {
    if (!EvaluationHelper.multirun) return;

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
    EvaluationHelper.printFileNames(fileArray, writeStream);

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

        writeStream.write(elapsedTime.toFixed(3) + ((i < fileArray.length - 1) ? ", " : ""));
      }
      writeStream.write("\n");
    }

    await vscode.workspace.fs.delete(URI.file(EvaluationHelper.workingFilePath));
  });


  test('Evaluate Single Verification', async () => {
    if (EvaluationHelper.multirun) return;

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
    EvaluationHelper.printFileNames(fileArray, writeStream);

    for (let j = 0; j < EvaluationHelper.repetitions; j++) {
      Verifier.flushCache();
      console.log("--------------------------------------");
      for (let i = 0; i < fileArray.length; i++) {
        let file = fileArray[i][0];
  
        let filePath = path.join(EvaluationHelper.evaluationFilesDir, file);
  
        let startTime = now();
        await EvaluationHelper.verify(EvaluationHelper.transformToFileUri(filePath), filePath);
        let endTime = now();
  
        let elapsedTime = (endTime - startTime) / 1000.0;

        console.log("Elapsed time for " + file + ": " + elapsedTime.toFixed(3) + "s");

        writeStream.write(elapsedTime.toFixed(3) + ((i < fileArray.length - 1) ? ", " : ""));
      }
      writeStream.write("\n");
    }
  });
});

