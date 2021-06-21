// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as path from 'path';
import { VerifierConfig, GobraSettings } from "../MessagePayloads";
import { Commands } from "../Helper";
import { Helper } from "../Helper";
import { State } from "../ExtensionState";
import { URI } from 'vscode-uri';
import * as fs from 'fs';
import * as glob from 'glob';
import { Verifier } from '../VerificationService';

const PROJECT_ROOT = path.join(__dirname, "..", "..");
const DATA_ROOT = path.join(PROJECT_ROOT, "src", "evaluate", "data");

export class EvaluationHelper {
  public static repetitions = 5;

  public static workingFilePath = path.join(DATA_ROOT, "working.gobra");
  private static evaluationResultsPath = PROJECT_ROOT;
  public static evaluationResultsFile = path.join(EvaluationHelper.evaluationResultsPath, "evaluationResults.txt");

  public static async getEvaluationFiles(): Promise<string[]> {
    const files = await new Promise<string[]>((resolve, reject) =>
    glob(
      "**/*.gobra",
      {
        cwd: DATA_ROOT,
      },
      (err, result) => {
        if (err) reject(err)
        else resolve(result)
      }
    ));
    return files.map(f => path.resolve(DATA_ROOT, f));
  }

  public static transformToFileUri(filePath: string): string {
    return URI.file(filePath).toString();
  }

  public static printFileNames(fileArray: string[], writeStream: fs.WriteStream): void {
    for (let i = 0; i < fileArray.length; i++) {
      let file = fileArray[i];
      writeStream.write(file + ((i < fileArray.length - 1) ? ", " : ""));
    }
    writeStream.write("\n");
  }

  public static async verify(fileUri: string, filePath: string): Promise<void> {
    const location = await Verifier.updateGobraTools(State.context, false)
    let config = new VerifierConfig(location);
    config.fileData.fileUri = fileUri;
    config.fileData.filePath = filePath;

    let settings = new EvaluationGobraSettings();
    config.gobraSettings = settings;

    State.client.sendNotification(Commands.setOpenFileUri, fileUri);
    State.client.sendNotification(Commands.verifyGobraFile, Helper.configToJson(config));

    return new Promise(resolve => {
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
