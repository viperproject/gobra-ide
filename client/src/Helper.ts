// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as child_process from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { URI } from 'vscode-uri';
import { GitHubReleaseAsset, Location } from 'vs-verification-toolbox';
import * as locate_java_home from '@viperproject/locate-java-home';
import { IJavaHomeInfo } from '@viperproject/locate-java-home/js/es5/lib/interfaces';
import { VerifierConfig, OverallVerificationResult, FileData, GobraSettings, PlatformDependendPath, GobraDependencies, PreviewData, HighlightingPosition } from "./MessagePayloads";


export class Helper {
  public static isWin = /^win/.test(process.platform);
  public static isLinux = /^linux/.test(process.platform);
  public static isMac = /^darwin/.test(process.platform);

  public static isServerMode(): boolean {
    const mode = vscode.workspace.getConfiguration("gobraSettings").get<boolean>("serverMode");
    return (mode == null) || mode;
  }

  public static getBuildChannel(): BuildChannel {
    const buildVersion = vscode.workspace.getConfiguration("gobraSettings").get("buildVersion");
    if (buildVersion === "Nightly") {
      return BuildChannel.Nightly;
    } else if (buildVersion === "Local") {
      return BuildChannel.Local;
    }
    return BuildChannel.Stable;
  }

  public static isAutoVerify(): boolean {
    const autoVerify = vscode.workspace.getConfiguration("gobraSettings").get<boolean>("autoVerify");
    return (autoVerify == null) || autoVerify;
  }

  public static verifyByDefaultPackage(): boolean {
    const verifyPackage = vscode.workspace.getConfiguration("gobraSettings").get<boolean>("verifyPackage");
    return (verifyPackage == null) || verifyPackage;
  }

  public static getTimeout(): number {
    const timeout = vscode.workspace.getConfiguration("gobraSettings").get<number>("timeout");
    if (timeout == null) {
      return 1000;
    } else {
      return timeout;
    }
  }


  public static registerCommand(commandId: string, command: (...args: any[]) => any, context: vscode.ExtensionContext): void {
    context.subscriptions.push(vscode.commands.registerCommand(commandId, command));
  }

  public static getFileName(path: URI): string {
    const filename = path.fsPath.split('/').pop();
    if (filename == null) {
      return "";
    } else {
      return filename;
    }
  }

  public static getCurrentlyOpenFileUri(): URI | undefined {
    if (vscode.window.activeTextEditor && vscode.window.activeTextEditor.document) {
      return vscode.window.activeTextEditor.document.uri;
    } else {
      return undefined;
    }
  }

  public static equal(uri1: URI, uri2: URI): Boolean {
    // there does not seem to be an equality function on URIs and object equality does
    // not work. Based on the specification of `.toString()` that guarantees that `URI.parse`
    // will reconstruct the URI, this equality check is based on `.toString()`:
    return uri1.toString() == uri2.toString();
  }

  public static getSelections(): vscode.Range[] {
    const editor = vscode.window.activeTextEditor
    if (editor) {
      return editor.selections.map(s => new vscode.Range(s.start, s.end));
    } else {
      return [];
    }
  }

  public static configToJson(config: VerifierConfig): string {
    return JSON.stringify(config);
  }

  public static gobraSettingsToJson(settings: GobraSettings): string {
    return JSON.stringify(settings);
  }

  public static fileDataToJson(fileData: FileData): string {
    return JSON.stringify(fileData);
  }

  public static previewDataToJson(previewData: PreviewData): string {
    return JSON.stringify(previewData);
  }

  public static jsonToOverallResult(json: string): OverallVerificationResult {
    return JSON.parse(json);
  }

  public static jsonToHighlightingPositions(json: string): HighlightingPosition[] {
    return JSON.parse(json);
  }

  public static getGobraSettings(): GobraSettings {
    let gobraSettings: unknown = vscode.workspace.getConfiguration("gobraSettings");
    return <GobraSettings> gobraSettings;
  }


  /**
    * Helper functions to get Paths of the dependencies.
    */
  private static getGobraDependencies(): GobraDependencies {
    let gobraDependencies: unknown = vscode.workspace.getConfiguration("gobraDependencies");
    return <GobraDependencies> gobraDependencies;
  }

  private static getPlatformPath(paths: PlatformDependendPath): string {
    if (Helper.isWin && paths.windows) return paths.windows;
    if (Helper.isLinux && paths.linux) return paths.linux;
    if (Helper.isMac && paths.mac) return paths.mac;
    return "";
  }

  private static getJavaHome(): Promise<IJavaHomeInfo> {
    return new Promise((resolve, reject) => {
      try {
        const options = {
          version: ">=11",
          mustBe64Bit: true,
          mustBeJDK: true // we currently disallow JREs
        };
        Helper.log("Searching for Java home...");
        locate_java_home.default(options, (err, javaHomes) => {
          if (err) {
            Helper.log(err.message);
            reject(err.message);
          } else {
            if (!Array.isArray(javaHomes) || javaHomes.length === 0) {
              const msg = "Could not find a 64-bit JDK with at least version 1.8. "
                + "Please install one and/or manually specify it in the Gobra settings.";
              Helper.log(msg);
              vscode.window.showErrorMessage(msg);
              reject(msg);
            } else {
              const javaHome = javaHomes[0];
              Helper.log(`Using Java home ${JSON.stringify(javaHome, null, 2)}`);
              resolve(javaHome);
            }
          }
        });
      } catch (err: any) {
        Helper.log(err.message);
        reject(err.message);
      }
    });
  }

  public static async getJavaPath(): Promise<string> {
    const configuredJavaBinary = Helper.getGobraDependencies().java.javaBinary;
    if (configuredJavaBinary == null || configuredJavaBinary == "") {
      // no java binary configured, search for it:
      const javaHome = await Helper.getJavaHome();
      return javaHome.executables.java;
    } else {
      return configuredJavaBinary;
    }
  }

  public static async getJavaCwd(): Promise<string> {
    const configuredCwd = Helper.getGobraDependencies().java.cwd;
    if (configuredCwd == null || configuredCwd === "") {
      const roots = vscode.workspace.workspaceFolders;
      if (roots == null || roots.length !== 1) {
        // if no workspace is available, simply use the OS' temp folder:
        Helper.log(`no unique workspace folder was found, the operating system's temp ` + 
          `folder will be used as Gobra Server's current working directory. ` +
          `This behavior can be changed by explicitly specifying a working directory in ` +
          `the settings as 'gobraDependencies.java.cwd'.`);
        return os.tmpdir();
      }
      return roots[0].uri.fsPath;
    }
    return configuredCwd;
  }

  public static getServerProcessArgs(serverBinary: string): string {
    const configuredArgString = Helper.getGobraDependencies().java.javaArguments
      .replace("$serverBinary$", `"${serverBinary}"`); // escape `serverBinary` in case it contains spaces
    return configuredArgString;
  }
  
  /**
    * Gets Gobra Tools Provider URL as stored in the settings.
    * Note that the returned URL might be invalid or correspond to one of the "special" URLs as specified in the README (e.g. to download a GitHub release asset)
    */
  public static getGobraToolsProvider(nightly: boolean = false): string {
    const gobraToolsProvider = Helper.getGobraDependencies().gobraToolsProvider;
    return Helper.getPlatformPath(nightly ? gobraToolsProvider.nightly : gobraToolsProvider.stable);
  }

  /**
   * Takes an url as input and checks whether it's a special URL to a GitHub release asset.
   * This function returns an object that indicates with the `isGitHubAsset` flag whether it is a GitHub asset or not. In addition, the `getUrl` function can
   * be called to lazily construct the URL for downloading the asset.
   */
  public static parseGitHubAssetURL(url: string): {isGitHubAsset: boolean, getUrl: () => Promise<string>} {
    const token = this.getGitHubToken();
    const latestRe = /^github.com\/([^/]+)\/([^/]+)\/releases\/latest\?asset-name=([^/?&]+)(&include-prereleases|)$/;
    const tagRe = /^github.com\/([^/]+)\/([^/]+)\/releases\/tags\/([^/?]+)\?asset-name=([^/?&]+)$/;
    const latestReMatches = url.match(latestRe);
    if (latestReMatches != null) {
      // match was found
      const owner = latestReMatches[1];
      const repo = latestReMatches[2];
      const assetName = latestReMatches[3];
      const includePrereleases = latestReMatches[4] === "&include-prereleases";
      const resolveGitHubUrl = () => GitHubReleaseAsset.getLatestAssetUrl(owner, repo, assetName, includePrereleases, token)
        .catch(Helper.rethrow(`Retrieving asset URL of latest GitHub release has failed `
          + `(owner: '${owner}', repo: '${repo}', asset-name: '${assetName}', include-prereleases: ${includePrereleases})`));
      return {
        isGitHubAsset: true,
        getUrl: resolveGitHubUrl,
      };
    }
    const tagReMatches = url.match(tagRe);
    if (tagReMatches != null) {
      // match was found
      const owner = tagReMatches[1];
      const repo = tagReMatches[2];
      const tag = tagReMatches[3];
      const assetName = tagReMatches[4];
      const resolveGitHubUrl = () => GitHubReleaseAsset.getTaggedAssetUrl(owner, repo, assetName, tag, token)
        .catch(Helper.rethrow(`Retrieving asset URL of a tagged GitHub release has failed `
            + `(owner: '${owner}', repo: '${repo}', tag: '${tag}', asset-name: '${assetName}')`));
      return {
        isGitHubAsset: true,
        getUrl: resolveGitHubUrl,
      };
    }
    // no match, return unmodified input URL:
    return {
      isGitHubAsset: false,
      getUrl: () => Promise.resolve(url),
    };
  }

  public static getGitHubToken(): string | undefined {
    return process.env["GITHUB_TOKEN"];
  }

  /**
   * Returns true if `getGobraToolsPath` should be wiped after activating the extension to ensure a clean system state.
   */
  public static cleanInstall(): boolean {
    const value = process.env["GOBRA_IDE_CLEAN_INSTALL"];
    return value != null && 
      (value == "1" || value.toUpperCase() == "TRUE");
  }

  /**
   * Returns true if Gobra-IDE runs in a non-interactive environment and confirmations should automatically be accepted.
   */
  public static assumeYes(): boolean {
    const value = process.env["GOBRA_IDE_ASSUME_YES"];
    return value != null && 
      (value == "1" || value.toUpperCase() == "TRUE");
  }

  public static getLocalGobraToolsPath(): ResolvedPath {
    const gobraToolsBasePath = Helper.getGobraDependencies().gobraToolsPaths.gobraToolsBasePath;
    return Helper.extractEnvVars(Helper.getPlatformPath(gobraToolsBasePath));
  }

  public static getServerJarPath(location: Location): ResolvedPath {
    if (Helper.getBuildChannel() == BuildChannel.Local) {
      const serverJarPaths = Helper.getGobraDependencies().gobraToolsPaths.serverJar;
      return Helper.extractEnvVars(Helper.getPlatformPath(serverJarPaths).replace("$gobraTools$", location.basePath));
    } else {
      // ignore `gobraToolsPaths`:
      return Helper.extractEnvVars(path.join(location.basePath, "server", "server.jar"));
    }
  }

  public static getBoogiePath(location: Location): ResolvedPath {
    if (Helper.getBuildChannel() == BuildChannel.Local) {
      const boogiePaths = Helper.getGobraDependencies().gobraToolsPaths.boogieExecutable;
      return Helper.extractEnvVars(Helper.getPlatformPath(boogiePaths).replace("$gobraTools$", location.basePath));
    } else {
      // ignore `gobraToolsPaths`:
      const binaryName = Helper.isWin ? "Boogie.exe" : "Boogie";
      return Helper.extractEnvVars(path.join(location.basePath, "boogie", "Binaries", binaryName));
    }
  }

  public static getZ3Path(location: Location): ResolvedPath {
    if (Helper.getBuildChannel() == BuildChannel.Local) {
      const z3Paths = Helper.getGobraDependencies().gobraToolsPaths.z3Executable;
      return Helper.extractEnvVars(Helper.getPlatformPath(z3Paths).replace("$gobraTools$", location.basePath));
    } else {
      // ignore `gobraToolsPaths`:
      const binaryName = Helper.isWin ? "z3.exe" : "z3";
      return Helper.extractEnvVars(path.join(location.basePath, "z3", "bin", binaryName));
    }
  }

  // taken from Viper-IDE:
  private static extractEnvVars(path: string): ResolvedPath {
    if (path && path.length > 2) {
      while (Helper.isWin && path.indexOf("%") >= 0) {
        const start = path.indexOf("%")
        const end = path.indexOf("%", start + 1);
        if (end < 0) {
          return { path: path, error: "unbalanced % in path: " + path };
        }
        const envName = path.substring(start + 1, end);
        const envValue = process.env[envName];
        if (!envValue) {
          return { path: path, error: "environment variable " + envName + " used in path " + path + " is not set" };
        }
        if (envValue.indexOf("%") >= 0) {
          return { path: path, error: "environment variable: " + envName + " must not contain '%': " + envValue };
        }
        path = path.substring(0, start) + envValue + path.substring(end + 1, path.length);
      }
      while (!Helper.isWin && path.indexOf("$") >= 0) {
        const index_of_dollar = path.indexOf("$")
        let index_of_closing_slash = path.indexOf("/", index_of_dollar + 1)
        if (index_of_closing_slash < 0) {
          index_of_closing_slash = path.length
        }
        const envName = path.substring(index_of_dollar + 1, index_of_closing_slash)
        const envValue = process.env[envName]
        if (!envValue) {
          return { path: path, error: "environment variable " + envName + " used in path " + path + " is not set" }
        }
        if (envValue.indexOf("$") >= 0) {
          return { path: path, error: "environment variable: " + envName + " must not contain '$': " + envValue };
        }
        path = path.substring(0, index_of_dollar) + envValue + path.substring(index_of_closing_slash, path.length)
      }
    }
    // Viper-IDE did not check whether path exists
    if (fs.existsSync(path)) {
      return { path: path };
    } else {
      return { path: path, error: `Expected path ${path} does not exist` };
    }
  }


  /**
    * Function to delay for 200ms to resolve weird bugs with switching of tabs.
    */
  public static delay() {
    return new Promise((resolve) => setTimeout(resolve, 400));
  }

  public static spawn(
    cmd: string, 
    args?: string[] | undefined, 
    options?: child_process.SpawnOptionsWithoutStdio | undefined
  ): Promise<Output> {
    Helper.log(`Gobra IDE: Running '${cmd} ${args ? args.join(' ') : ''}'`);
    return new Promise((resolve, reject) => {
      let stdout = '';
      let stderr = '';

      const proc = child_process.spawn(cmd, args, options);

      proc.stdout.on('data', (data) => stdout += data);
      proc.stderr.on('data', (data) => stderr += data);
      proc.on('close', (code) => {
        Helper.log("┌──── Begin stdout ────┐");
        Helper.log(stdout);
        Helper.log("└──── End stdout ──────┘");
        Helper.log("┌──── Begin stderr ────┐");
        Helper.log(stderr);
        Helper.log("└──── End stderr ──────┘");
        resolve({ stdout, stderr, code });
      });
      proc.on('error', (err) => {
        Helper.log("┌──── Begin stdout ────┐");
        Helper.log(stdout);
        Helper.log("└──── End stdout ──────┘");
        Helper.log("┌──── Begin stderr ────┐");
        Helper.log(stderr);
        Helper.log("└──── End stderr ──────┘");
        Helper.log(`Error: ${err}`);
        reject(err);
      });
    });
  }

  public static rethrow(msg: string): (originalReason: any) => PromiseLike<never> {
    return (originalReason: any) => {
      Helper.log(originalReason);
      throw new Error(`${msg} (reason: '${originalReason}')`);
    }
  }

  private static _channel: vscode.OutputChannel;
  public static log(msg: string): void {
    console.log(`[Gobra IDE] ${msg}`);
    if (!this._channel) {
      this._channel = vscode.window.createOutputChannel("Gobra IDE");
    }
    this._channel.appendLine(msg);
  }

  private static _serverChannel: vscode.OutputChannel;
  public static logServer(msg: string): void {
    console.log(`[Gobra IDE - Server] ${msg}`);
    if (!this._serverChannel) {
      this._serverChannel = vscode.window.createOutputChannel("Gobra IDE - Server");
    }
    this._serverChannel.appendLine(msg);
  }
}


export class Commands {
  /**
    * Commands handled by Gobra-Server
    */
  public static verify = "gobraServer/verify";
  public static changeFile = "gobraServer/changeFile";
  public static flushCache = "gobraServer/flushCache";
  public static goifyFile = "gobraServer/goifyFile";
  public static gobrafyFile = "gobraServer/gobrafyFile";
  public static setOpenFileUri = "gobraServer/setOpenFileUri";
  public static codePreview = "gobraServer/codePreview";

  /**
    * Commands handled by Client (VSCode)
    */
  public static overallResult = "gobraServer/overallResult";
  public static noVerificationInformation = "gobraServer/noVerificationInformation";
  public static verificationProgress = "gobraServer/verificationProgress";
  public static finishedVerification = "gobraServer/finishedVerification";
  public static verificationException = "gobraServer/verificationException";
  public static finishedGoifying = "gobraServer/finishedGoifying";
  public static finishedGobrafying = "gobraServer/finishedGobrafying";
  public static finishedViperCodePreview = "gobraServer/finishedViperCodePreview";
  public static finishedInternalCodePreview = "gobraServer/finishedInternalCodePreview";
}

// Defines the texts in statusbars ...
export class Texts {
  public static runningVerification = "Verification of ";
  public static helloGobra = "Hello from Gobra";
  public static flushCache = "Flush Cache";
  public static installingGobraToolsConfirmationMessage = "Gobra-IDE requires Gobra and additional tools. Do you want to install them?";
  public static installingGobraToolsConfirmationYesButton = "Yes";
  public static installingGobraToolsConfirmationNoButton = "No";
  public static gobraToolsInstallationDenied = "Installation of Gobra and required tools has been denied. Restart Visual Studio Code and allow their installation.";
  public static updatingGobraTools = "Updating Gobra Tools";
  public static ensuringGobraTools = "Ensuring Gobra Tools";
  public static successfulUpdatingGobraTools = "Successfully updated Gobra Tools. Please restart the IDE.";
  public static successfulEnsuringGobraTools = "Successfully ensured Gobra Tools.";
  public static changedBuildVersion = "Changed the build version of Gobra Tools. Please restart the IDE.";
  public static javaLocation(path: string, version: string) {
    // note that VSCode (at least currently) strips new-line characters. Thus, make sure it is nonetheless somewhat readable
    return `Gobra uses java located at: \n\`${path}\`.\n\nThe java version is: \n\`${version}\`.`;
  }
}

export class Color {
  public static green = "lightgreen";
  public static white = "white";
  public static red = "red";
  public static orange = "orange";
  public static darkgreen = "darkgreen";
}


/**
  * Commands contributed to VS-Code.
  */
export class ContributionCommands {
  public static flushCache = "gobra.flushCache";
  public static goifyFile = "gobra.goifyFile";
  public static gobrafyFile = "gobra.gobrafyFile";
  /** verifies file or package depending on settings */
  public static verify = "gobra.verify";
  public static verifyFile = "gobra.verifyFile";
  public static verifyPackage = "gobra.verifyPackage";
  public static updateGobraTools = "gobra.updateGobraTools";
  public static showViperCodePreview = "gobra.showViperCodePreview";
  public static showInternalCodePreview = "gobra.showInternalCodePreview";
  public static showJavaPath = "gobra.showJavaPath";
}


/**
  * File schemes used for displaying the preview of the translations.
  */
export class FileSchemes {
  public static viper = "viperPreview";
  public static internal = "internalPreview";
}

/**
  * Uris for the files which are used for the preview of code.
  */
export class PreviewUris {
  public static viper = vscode.Uri.parse(FileSchemes.viper + ":viperPreview.vpr/");
  public static internal = vscode.Uri.parse(FileSchemes.internal + ":internalPreview.gobra/");
}

export interface Output {
  stdout: string;
  stderr: string;
  code: number | null;
}

export enum BuildChannel {
  Stable = "Stable",
  Nightly = "Nightly",
  Local = "Local"
}

export interface ResolvedPath {
  path: string,
  error?: string
}
