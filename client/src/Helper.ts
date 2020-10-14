// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import * as vscode from 'vscode';
import { URI } from 'vscode-uri';
import { VerifierConfig, OverallVerificationResult, FileData, GobraSettings, PlatformDependendPath, GobraDependencies, PreviewData, HighlightingPosition } from "./MessagePayloads";
import * as locate_java_home from 'locate-java-home';
import * as path from 'path';
import * as child_process from 'child_process';
import { GitHubReleaseAsset } from 'vs-verification-toolbox';


export class Helper {
  public static isWin = /^win/.test(process.platform);
  public static isLinux = /^linux/.test(process.platform);
  public static isMac = /^darwin/.test(process.platform);

  public static isServerMode(): boolean {
    return vscode.workspace.getConfiguration("gobraSettings").get("serverMode");
  }

  public static isNightly(): boolean {
    return vscode.workspace.getConfiguration("gobraSettings").get("buildVersion") == "nightly";
  }

  public static isAutoVerify(): boolean {
    return vscode.workspace.getConfiguration("gobraSettings").get("autoVerify");
  }

  public static getTimeout(): number {
    return vscode.workspace.getConfiguration("gobraSettings").get("timeout");
  }


  public static registerCommand(commandId: string, command: (...args: any[]) => any, context: vscode.ExtensionContext): void {
    context.subscriptions.push(vscode.commands.registerCommand(commandId, command));
  }

  public static getFilePath(): string {
    if (vscode.window.activeTextEditor && vscode.window.activeTextEditor.document) {
      return vscode.window.activeTextEditor.document.fileName;
    } else {
      return "";
    }
  }

  public static getFileName(path: string): string {
    return path.split('/').pop();
  }

  public static getFileUri(): string {
    return URI.file(Helper.getFilePath()).toString();
  }

  public static getSelections(): vscode.Range[] {
    return vscode.window.activeTextEditor.selections.map(s => new vscode.Range(s.start, s.end));
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
    return null;
  }

  /**
    * Specifies the Path added by the zip extractor.
    */
  private static extractionAddition(): string {
    return Helper.isWin ? "\\Gobra\\GobraTools" : "/Gobra/GobraTools"
  }

  private static getJavaHome(): Promise<string> {
    return new Promise((resolve, reject) => {
      try {
        const options = {
          version: ">=1.8"
        };
        console.log("Searching for Java home...");
        locate_java_home.default(options, (err, javaHomes) => {
          if (err) {
            console.error(err.message);
            reject(err.message);
          } else {
            if (!Array.isArray(javaHomes) || javaHomes.length === 0) {
              console.log("Could not find Java home");
              reject("no Java home found");
            } else {
              const javaHome = javaHomes[0];
              console.log("Using Java home", javaHome);
              resolve(javaHome.path);
            }
          }
        });
      } catch (err) {
        console.error(err.message);
        reject(err.message);
      }
    });
  }

  public static async getJavaPath(): Promise<string> {
    return path.join(
      await Helper.getJavaHome(),
      "bin",
      "java" + (Helper.isWin ? ".exe" : "")
    );
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

  public static getGitHubToken(): string {
    return process.env["GITHUB_TOKEN"];
  }

  /**
    * Get Location where Gobra Tools will be installed.
    */
  public static getGobraToolsPath(): string {
    let gobraToolsPaths = Helper.getGobraDependencies().gobraToolsPaths.gobraToolsPath;
    return Helper.getPlatformPath(gobraToolsPaths);
  }

  public static getServerJarPath(nightly: boolean = false): string {
    let serverJarPaths = Helper.getGobraDependencies().gobraToolsPaths.serverJar;
    return Helper.getPlatformPath(serverJarPaths).replace("$gobraTools$", Helper.getGobraToolsPath() + Helper.extractionAddition());
  }

  
  public static getBoogiePath(nightly: boolean = false): string {
    let boogiePaths = Helper.getGobraDependencies().gobraToolsPaths.boogieExecutable;
    return Helper.getPlatformPath(boogiePaths).replace("$gobraTools$", Helper.getGobraToolsPath() + Helper.extractionAddition());
  }

  public static getZ3Path(nightly: boolean = false): string {
    let z3Paths = Helper.getGobraDependencies().gobraToolsPaths.z3Executable;
    return Helper.getPlatformPath(z3Paths).replace("$gobraTools$", Helper.getGobraToolsPath() + Helper.extractionAddition());
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
    console.log(`Gobra IDE: Running '${cmd} ${args ? args.join(' ') : ''}'`);
    return new Promise((resolve, reject) => {
      let stdout = '';
      let stderr = '';

      const proc = child_process.spawn(cmd, args, options);

      proc.stdout.on('data', (data) => stdout += data);
      proc.stderr.on('data', (data) => stderr += data);
      proc.on('close', (code) => {
        console.log("┌──── Begin stdout ────┐");
        console.log(stdout);
        console.log("└──── End stdout ──────┘");
        console.log("┌──── Begin stderr ────┐");
        console.log(stderr);
        console.log("└──── End stderr ──────┘");
        resolve({ stdout, stderr, code });
      });
      proc.on('error', (err) => {
        console.log("┌──── Begin stdout ────┐");
        console.log(stdout);
        console.log("└──── End stdout ──────┘");
        console.log("┌──── Begin stderr ────┐");
        console.log(stderr);
        console.log("└──── End stderr ──────┘");
        console.log(`Error: ${err}`);
        reject(err);
      });
    });
  }

  public static rethrow(msg: string): (originalReason: any) => PromiseLike<never> {
    return (originalReason: any) => {
      console.error(originalReason);
      throw new Error(`${msg} (reason: '${originalReason}')`);
    }
  }
}


export class Commands {
  /**
    * Commands handled by Gobra-Server
    */
  public static verifyGobraFile = "gobraServer/verifyGobraFile";
  public static verifyGoFile = "gobraServer/verifyGoFile";
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
  public static updatingGobraTools = "Updating Gobra Tools";
  public static installingGobraTools = "Installing Gobra Tools";
  public static successfulUpdatingGobraTools = "Successfully updated Gobra Tools. Please restart the IDE.";
  public static successfulInstallingGobraTools = "Successfully installed Gobra Tools.";
  public static changedBuildVersion = "Changed the build version of Gobra Tools. Please restart the IDE.";
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
  public static verifyFile = "gobra.verifyFile";
  public static updateGobraTools = "gobra.updateGobraTools";
  public static showViperCodePreview = "gobra.showViperCodePreview";
  public static showInternalCodePreview = "gobra.showInternalCodePreview";
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
  code: number;
}
