// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import { after } from 'mocha';
import * as fs from 'fs';
import * as vscode from 'vscode';

import { TestHelper } from "../test/TestHelper";
import { EvaluationHelper } from "../evaluate/EvaluationHelper";

import { Verifier } from '../VerificationService';

suite('Evaluation Suite', () => {
  after(() => {
    vscode.window.showInformationMessage('All evaluations done!');
  });

  test('Evaluate Single Verification', async () => {
    const now = require("performance-now");

    let writeStream = fs.createWriteStream(EvaluationHelper.evaluationResultsFile);

    // start extension and therefore the language server
    await TestHelper.startExtension(EvaluationHelper.workingFilePath);

    /**
      * Iterate over all files in the evaluationFiles directory
      */
     const filePaths = await EvaluationHelper.getEvaluationFiles();
    EvaluationHelper.printFileNames(filePaths, writeStream);

    for (let j = 0; j < EvaluationHelper.repetitions; j++) {
      Verifier.flushCache();
      console.log("--------------------------------------");
      for (let i = 0; i < filePaths.length; i++) {
        let filePath = filePaths[i];
  
        let startTime = now();
        await EvaluationHelper.verify(EvaluationHelper.transformToFileUri(filePath));
        let endTime = now();
  
        let elapsedTime = (endTime - startTime) / 1000.0;

        console.log("Elapsed time for " + filePath + ": " + elapsedTime.toFixed(3) + "s");

        writeStream.write(elapsedTime.toFixed(3) + ((i < filePaths.length - 1) ? ", " : ""));
      }
      writeStream.write("\n");
    }
  });
});

