// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import { Helper } from "./Helper";
import * as vscode from 'vscode';
import { URI } from 'vscode-uri';
import { Location } from "vs-verification-toolbox";

export class FileData {
  filePath: string;
  fileUri: string;

  constructor(fileUri: URI) {
    this.filePath = fileUri.fsPath;
    this.fileUri = fileUri.toString();
  }
}

export class VerifierConfig {
  fileData: FileData[];
  gobraSettings: GobraSettings;
  z3Executable: string;
  boogieExecutable: string;

  constructor(location: Location, files: FileData[]) {
    this.fileData = files;
    this.gobraSettings = Helper.getGobraSettings();

    this.z3Executable = Helper.getZ3Path(location);
    this.boogieExecutable = Helper.getBoogiePath(location);
  }
}

export class PreviewData {
  fileData: FileData[];
  internalPreview: boolean;
  viperPreview: boolean;
  selections: vscode.Range[];

  constructor(fileData: FileData[], internalPreview: boolean, viperPreview: boolean, selections: vscode.Range[]) {
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
  backend: string;
  serverMode: boolean;
  debug: boolean;
  eraseGhost: boolean;
  goify: boolean;
  unparse: boolean;
  printInternal: boolean;
  printViper: boolean;
  parseOnly: boolean;
  loglevel: string;
  moduleName: string;
  includeDirs: string[];
}

export interface JavaSettings {
  javaBinary: string;
  cwd: string;
  javaArguments: string;
}

export interface PathSettings {
  gobraToolsBasePath: PlatformDependendPath;
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
  fileUris: string[];
  success: boolean;
  message: string;

  constructor(fileUris: string[], success: boolean, message: string) {
    this.fileUris = fileUris;
    this.success = success;
    this.message = message;
  }
    
}
