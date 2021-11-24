<img src="images/gobra.png" height="250">

Gobra IDE directly integrates verification with Gobra into VSCode.

**Note:** Gobra as well as Gobra IDE are both prototypes and under active development.

## First Usage
1. Install the [requirements](#requirements)
2. Install [this extension](https://marketplace.visualstudio.com/items?itemName=viper-admin.gobra-ide) from the Visual Studio Marketplace
3. Open a Gobra or Go file to activate this extension. At the first activation, this extension will download Gobra automatically.

Opening or modifying a Gobra program (file extension `.gobra`) will automatically trigger its verification.
In addition, a verification can also manually be triggered from the command palette (View -> Command Palette, or Shift+Ctrl+P on Ubuntu) by running the command `Gobra: Verify currently opened file`.
The progress of a Gobra verification is indicated in the status bar, as a message "Verification of ...".
The results of completed verifications are displayed in the status bar as well.
Any verification failures are detailed in the "Problems" tab (which can be opened with View -> Problems).

To update Gobra to the latest version, run the command `Gobra: Update Gobra Tools` in the command palette. This will download the latest stable (default) or nightly release, depending on your settings.

## Features
### Verification of Go programs
Verification of Go programs (file extension `.go`) is supported by this extension as well.
Program specifications have to be annotated in comments starting either with `//@`, or starting and ending with `/*@` and `@*/`, respectively.

Note that automatic verification is not supported for `.go` programs.
For these programs, the verification must be triggered manually from the command palette, using the command `Gobra: Verify currently opened file`.

Any Gobra program can be converted to a Go program and back by running the commands `Gobra: Goify currently opened file` and `Gobra: Gobrafy currently opened file`, respectively.

### Verification Result Caching
This extension caches verification results by default.
The cache can be flushed by running the command `Gobra: Flush ViperServer cache` from the command palette.

### Verification of Packages
A package can be split across multiple `.go` or `.gobra` files.
To verify them together, run the command `Gobra: Verify currently opened package` while having one of these files open.
Alternatively, there is the `Gobra: Verify currently opened file or package` command that either verifies a single file or a package depending on your Gobra settings.

Also relevant when working with larger projects is the option to specify directories in which Gobra tries to resolve imports and a module name.
Include directories can be specified in the settings as `gobraSettings.includeDirs`.
Gobra has partial support for modules meaning that the current module name can be specified in the settings as `gobraSettings.moduleName`.
Importing a package in the current module amounts to an import path consisting of the module name and the relative path to the desired package.
When resolving such an import, Gobra simply drops the module name prefix.
However, there is currently no dedicated support for resolving packages in external modules.
This means that Gobra tries to resolve such packages by searching for a sub-directory with the external module name in the specified list of include directories.

Note that VSCode allows workspace-specific settings by creating the file `.vscode/settings.json` relative to the root of a workspace.
These workspace-specific settings take precendence over the usual user settings.

### Show Intermediate Representations
Right clicking on some selected code in a Gobra program reveals two actions, "Show internal representation preview" and "Show Viper code preview".
Both actions result in a preview, on the right-hand side, of the internal representation used by Gobra and the resulting code in the Viper intermediate language, respectively.
The translated parts of the selected code are marked in green.

## Requirements
- [Java JDK, 64 bit, version 1.8 or later](https://www.java.com/en/download/)

Gobra IDE automatically looks for a java installation.
The command `Gobra: Show path to Java binary` in the command palette will show the path to the Java binary as well as its version.
