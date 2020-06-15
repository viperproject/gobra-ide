import { Helper } from "./Helper";
import * as vscode from "vscode";

export class FileData {
  filePath: string;
  fileUri: string;

  constructor() {
    this.filePath = Helper.getFilePath();
    this.fileUri = Helper.getFileUri();
  }
}


export class ClientConfig {
  backend: string;
  serverMode: boolean;
  z3Exe: string;
  boogieExe: string;
  debug: boolean;
  eraseGhost: boolean;
  unparse: boolean;
  printInternal: boolean;
  printViper: boolean;
  parseOnly: boolean;
  logLevel: string;

  constructor(config: vscode.WorkspaceConfiguration) {
    this.backend = config.get("backend");
    this.serverMode = config.get("serverMode");
    this.z3Exe = "D:\\Daten_Silas\\Downloads\\ViperToolsWin\\z3\\bin\\z3.exe";
    this.boogieExe = "D:\\Daten_Silas\\Downloads\\ViperToolsWin\\boogie\\Binaries\\Boogie.exe";
    this.debug = config.get("debug");
    this.eraseGhost = config.get("eraseGhost");
    this.unparse = config.get("unparse");
    this.printInternal = config.get("printInternal");
    this.printViper = config.get("printViper");
    this.parseOnly = config.get("parseOnly");
    this.logLevel = config.get("loglevel");
  }
}

export class VerifierConfig {
  fileData: FileData;
  gobraSettings: GobraSettings;
  z3Executable: string;
  boogieExecutable: string;

  constructor() {
    this.fileData = new FileData();
    this.gobraSettings = Helper.getGobraSettings();

    this.z3Executable = Helper.getZ3Path();
    this.boogieExecutable = Helper.getBoogiePath();
  }
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

export interface PathSettings {
  viperToolsPath: PlatformDependendPath;
  z3Executable: PlatformDependendPath;
  boogieExecutable: PlatformDependendPath;
}

export interface GobraDependencies {
  viperToolsPaths: PathSettings;
  viperToolsProvider: PlatformDependendPath;
}

export interface PlatformDependendPath {
  windows?: string;
  linux?: string;
  mac?: string;
}

export class OverallVerificationResult {
  success: boolean;
  message: string;

  constructor(success: boolean, message: string) {
    this.success = success;
    this.message = message;
  }
    
}
