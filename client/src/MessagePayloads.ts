// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import { Helper } from "./Helper";
import * as vscode from 'vscode';
import { Location } from "vs-verification-toolbox";

export class FileData {
  filePath: string;
  fileUri: string;

  constructor() {
    this.filePath = Helper.getFilePath();
    this.fileUri = Helper.getFileUri();
  }
}

export class VerifierConfig {
  fileData: FileData;
  gobraSettings: GobraSettings;
  z3Executable: string;
  boogieExecutable: string;

  constructor(location: Location) {
    this.fileData = new FileData();
    this.gobraSettings = Helper.getGobraSettings();

    this.z3Executable = Helper.getZ3Path(location);
    this.boogieExecutable = Helper.getBoogiePath(location);
  }
}

export class PreviewData {
  fileData: FileData;
  internalPreview: boolean;
  viperPreview: boolean;
  selections: vscode.Range[];

  constructor(fileData: FileData, internalPreview: boolean, viperPreview: boolean, selections: vscode.Range[]) {
    this.fileData = fileData;
    this.selections = selections;
    this.internalPreview = internalPreview;
    this.viperPreview = viperPreview;
  }
}

export interface HighlightingPosition {
  startIndex: number;
  length: number;
}

export interface GobraSettings {
  serverMode: boolean;
  debug: boolean;
  eraseGhost: boolean;
  unparse: boolean;
  printInternal: boolean;
  printViper: boolean;
  parseOnly: boolean;
  loglevel: string;
  backend: string;
}

export interface JavaSettings {
  javaBinary: string;
  javaArguments: string;
}

export interface PathSettings {
  z3Executable: PlatformDependendPath;
  boogieExecutable: PlatformDependendPath;
  serverJar: PlatformDependendPath;
}

export interface ProviderSettings {
  stable: PlatformDependendPath;
  nightly: PlatformDependendPath;
}

export interface GobraDependencies {
  java: JavaSettings;
  gobraToolsPaths: PathSettings;
  gobraToolsProvider: ProviderSettings;
}

export interface PlatformDependendPath {
  windows?: string;
  linux?: string;
  mac?: string;
}

export class OverallVerificationResult {
  fileUri: string;
  success: boolean;
  message: string;

  constructor(fileUri: string, success: boolean, message: string) {
    this.fileUri = fileUri;
    this.success = success;
    this.message = message;
  }
    
}
