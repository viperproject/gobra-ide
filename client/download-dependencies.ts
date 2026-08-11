// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

// This script assembles the Gobra tools in `dependencies/GobraTools` such that they can be bundled
// with the extension (see viper-ide PR #434 for the corresponding change in Viper-IDE):
// - Z3 and Boogie are downloaded for the given target platform in the versions pinned in
//   `z3-version` and `boogie-version`, respectively.
// - `server.jar` is taken from the server built in this repository (`sbt assembly` in `server/`).

import AdmZip from 'adm-zip';
import * as fs from 'fs/promises';
import { chmodSync } from 'fs';
import * as path from 'path';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import * as rimraf from 'rimraf';
import { strict as assert } from 'assert';

const outputDir = path.resolve(import.meta.dirname, 'dependencies', 'GobraTools');
// note that a folder within `dependencies` is used as scratch space since the repository's `tmp`
// folder is in use for other purposes:
const tmpFolder = path.resolve(import.meta.dirname, 'dependencies', 'tmp');

const boogieVersionFile = 'boogie-version';
const boogieOutputDir = path.resolve(outputDir, 'boogie');
const boogieLinuxDownloadUrl = (version: string) => `https://github.com/viperproject/boogie-builder/releases/download/${version}/boogie-linux.zip`;
const boogieWindowsDownloadUrl = (version: string) => `https://github.com/viperproject/boogie-builder/releases/download/${version}/boogie-win.zip`;
const boogieMacDownloadUrl = (version: string) => `https://github.com/viperproject/boogie-builder/releases/download/${version}/boogie-osx.zip`;
const boogieMacARMDownloadUrl = (version: string) => `https://github.com/viperproject/boogie-builder/releases/download/${version}/boogie-osx-arm.zip`;

const z3VersionFile = 'z3-version';
const z3OutputDir = path.resolve(outputDir, 'z3');
const z3LinuxDownloadUrl = (version: string) => `https://github.com/Z3Prover/z3/releases/download/z3-${version}/z3-${version}-x64-ubuntu-16.04.zip`;
const z3WindowsDownloadUrl = (version: string) => `https://github.com/Z3Prover/z3/releases/download/z3-${version}/z3-${version}-x64-win.zip`;
const z3MacDownloadUrl = (version: string) => `https://github.com/Z3Prover/z3/releases/download/z3-${version}/z3-${version}-x64-osx-10.14.6.zip`;
const z3MacARMDownloadUrl = (version: string) => {
  // The non-'4.8.7' URL will only work for '4.9.0' and above (such a version would break the above two URLs though, since they now build for `osx-10.16` and `glibc-2.35`)
  if (version == '4.8.7') {
    return 'https://github.com/viperproject/boogie-builder/raw/master/prebuilt_z3/z3-4.8.7-arm64-osx.zip';
  } else {
    return `https://github.com/Z3Prover/z3/releases/download/z3-${version}/z3-${version}-arm64-osx-11.zip`;
  }
}

const serverJarSourcePath = path.resolve(import.meta.dirname, '..', 'server', 'target', 'scala-2.13', 'server.jar');
const serverOutputDir = path.resolve(outputDir, 'server');


const LinuxOption = 'linux-x64';
const MacOption = 'darwin-x64';
const MacARMOption = 'darwin-arm64';
const WindowsOption = 'win32-x64';
type Target = typeof LinuxOption | typeof MacOption | typeof MacARMOption | typeof WindowsOption;

async function main() {
    const isWindows = /^win/.test(process.platform);
    const isLinux = /^linux/.test(process.platform);
    const isMac = /^darwin/.test(process.platform);
    const isArm = process.arch === 'arm64';
    let defaultPlatform: string | undefined;
    if (isLinux) {
      defaultPlatform = LinuxOption;
    } else if (isMac) {
      if (isArm) {
        defaultPlatform = MacARMOption;
      } else {
        defaultPlatform = MacOption;
      }
    } else if (isWindows) {
      defaultPlatform = WindowsOption;
    } else {
      defaultPlatform = undefined;
    }

    const argv = await yargs(hideBin(process.argv))
      .option('target', {
        alias: 't',
        describe: 'Target platform for which dependencies should be downloaded',
        choices: [LinuxOption, MacOption, MacARMOption, WindowsOption],
        default: defaultPlatform
      })
      .help()
      .argv;

    if (!argv.target) {
      throw new Error(`No target platform detected or specified`);
    }
    if (argv.target !== LinuxOption && argv.target !== MacOption && argv.target !== MacARMOption && argv.target !== WindowsOption) {
      throw new Error(`Invalid target platform specified`);
    }
    // TS now infers that `argv.target` is of type `Target`
    const target = argv.target;

    await rimraf.rimraf(outputDir);
    await rimraf.rimraf(tmpFolder);

    const boogieVersion = (await fs.readFile(boogieVersionFile)).toString().trim();
    const z3Version = (await fs.readFile(z3VersionFile)).toString().trim();


    // copy the server built in this repository
    console.info(`copying '${serverJarSourcePath}'...`);
    try {
      await fs.access(serverJarSourcePath);
    } catch {
      throw new Error(`'${serverJarSourcePath}' does not exist. Build it by running 'sbt assembly' in the 'server' folder first.`);
    }
    await fs.mkdir(serverOutputDir, { recursive: true });
    await fs.copyFile(serverJarSourcePath, path.resolve(serverOutputDir, 'server.jar'));


    // download Boogie. The zip contains a single folder holding the binaries:
    const boogieExtractionDir = await downloadAndExtract(getBoogieUrl(target, boogieVersion), 'boogie');
    const boogieSubfolders = await fs.readdir(boogieExtractionDir);
    assert(boogieSubfolders.length === 1);
    await fs.mkdir(boogieOutputDir, { recursive: true });
    await fs.rename(
      path.resolve(boogieExtractionDir, boogieSubfolders[0]),
      path.resolve(boogieOutputDir, 'Binaries'));
    makeExecutable(target, path.resolve(boogieOutputDir, 'Binaries', 'Boogie'));


    // download z3. The zip contains a single folder holding `bin/z3` resp. `bin/z3.exe`:
    const z3ExtractionDir = await downloadAndExtract(getZ3Url(target, z3Version), 'z3');
    const z3Subfolders = await fs.readdir(z3ExtractionDir);
    assert(z3Subfolders.length === 1);
    const z3BinName = target == WindowsOption ? 'z3.exe' : 'z3';
    await fs.mkdir(path.resolve(z3OutputDir, 'bin'), { recursive: true });
    await fs.rename(
      path.resolve(z3ExtractionDir, z3Subfolders[0], 'bin', z3BinName),
      path.resolve(z3OutputDir, 'bin', z3BinName));
    makeExecutable(target, path.resolve(z3OutputDir, 'bin', z3BinName));

    await rimraf.rimraf(tmpFolder);
    console.info(`the Gobra tools have been assembled in '${outputDir}'`);
}

/** downloads the zip at `url` and extracts it into a folder named `name` within `tmpFolder` */
async function downloadAndExtract(url: string, name: string): Promise<string> {
    console.info(`downloading '${url}'...`);
    const headers: Record<string, string> = {};
    const token = process.env['GITHUB_TOKEN'];
    if (token) {
      headers['Authorization'] = `token ${token}`;
    }
    const response = await fetch(url, { headers, redirect: 'follow' });
    if (!response.ok) {
      throw new Error(`downloading '${url}' has failed with status ${response.status} ${response.statusText}`);
    }
    const zip = new AdmZip(Buffer.from(await response.arrayBuffer()));
    const extractionDir = path.resolve(tmpFolder, name);
    zip.extractAllTo(extractionDir, true);
    return extractionDir;
}

/** marks the file as executable since zip extraction does not restore the executable bit */
function makeExecutable(target: Target, filePath: string): void {
    if (target !== WindowsOption && process.platform !== 'win32') {
      chmodSync(filePath, '755');
    }
}

function getBoogieUrl(target: Target, version: string): string {
  switch (target) {
    case LinuxOption:
      return boogieLinuxDownloadUrl(version);
    case MacOption:
      return boogieMacDownloadUrl(version);
    case MacARMOption:
      return boogieMacARMDownloadUrl(version);
    case WindowsOption:
      return boogieWindowsDownloadUrl(version);
  }
}

function getZ3Url(target: Target, version: string): string {
  switch (target) {
    case LinuxOption:
      return z3LinuxDownloadUrl(version);
    case MacOption:
      return z3MacDownloadUrl(version);
    case MacARMOption:
      return z3MacARMDownloadUrl(version);
    case WindowsOption:
      return z3WindowsDownloadUrl(version);
  }
}

main().catch((err) => {
	console.error(`downloading dependencies has ended with an error: ${err}`);
	process.exit(1);
});
