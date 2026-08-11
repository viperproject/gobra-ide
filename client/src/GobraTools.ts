// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { Location } from 'vs-verification-toolbox';
import { BuildChannel, Helper } from './Helper.js';

/**
  * Locates the Gobra tools for the configured build version. For `BuiltIn`, these are the tools
  * bundled with the extension; for `External`, the configured `gobraToolsBasePath` is used.
  */
export async function locateGobraTools(context: vscode.ExtensionContext): Promise<Location> {
  const selectedChannel = Helper.getBuildChannel();
  Helper.log(`Locating the Gobra tools for build version ${selectedChannel}`);
  let basePath: string;
  if (selectedChannel === BuildChannel.External) {
    const externalPath = Helper.getLocalGobraToolsPath();
    if (externalPath.error != null) {
      vscode.window.showErrorMessage(externalPath.error);
      throw new Error(externalPath.error);
    }
    basePath = externalPath.path;
  } else {
    basePath = path.join(context.extension.extensionPath, 'dependencies', 'GobraTools');
    if (!fs.existsSync(basePath)) {
      const msg = `The Gobra tools bundled with the extension were not found at '${basePath}'. The extension's installation seems corrupted -- please reinstall the extension.`;
      vscode.window.showErrorMessage(msg);
      throw new Error(msg);
    }
  }
  const location = new Location(basePath);
  setPermissions(location);
  return location;
}

/**
  * Marks z3 and Boogie as executable since unpacking the extension does not preserve the
  * executable bit.
  */
function setPermissions(location: Location): void {
  if (Helper.isLinux || Helper.isMac) {
    const z3Path = Helper.getZ3Path(location);
    if (z3Path.error == null) {
      fs.chmodSync(z3Path.path, '755');
    }
    const boogiePath = Helper.getBoogiePath(location);
    if (boogiePath.error == null) {
      fs.chmodSync(boogiePath.path, '755');
    }
  }
}
