import { Helper } from "./Helper";

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
  gobraToolsPath: PlatformDependendPath;
  stableBuildPath: PlatformDependendPath;
  nightlyBuildPath: PlatformDependendPath;
  z3Executable: PlatformDependendPath;
  boogieExecutable: PlatformDependendPath;
  serverJar: PlatformDependendPath;
}

export interface GobraDependencies {
  gobraToolsPaths: PathSettings;
  gobraToolsProvider: PlatformDependendPath;
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
