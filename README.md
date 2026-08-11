# Visual Studio Code extension for Gobra

[![Test Status](https://github.com/viperproject/gobra-ide/actions/workflows/test.yml/badge.svg?branch=master)](https://github.com/viperproject/gobra-ide/actions?query=workflow%3Atest+branch%3Amaster)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL%202.0-brightgreen.svg)](./LICENSE)

This Visual Studio Code extension allows interactive verification using Gobra.  
Additionally, other visual features such as inspecting the intermediate
translation of Gobra are available.


## Installation Instructions

1. Clone this repository
2. Change directory to the gobra-ide directory created in the previous step.
3. Initialize and fetch the submodules: `git submodule update --init --recursive`


### Assemble the server:
1. Navigate to the server folder: `cd server`
2. Assemble the binary: `sbt assembly` (or `sbt -java-home <path to java home> assembly` to provide a particular JDK)

Note: `sbt` has to be installed, which in turn requires a JDK.
`adoptopenjdk13` is known to *not* be compatible with the currently used sbt version.
JDK version 11 is working (assuming [Homebrew](https://brew.sh) is used):

`brew tap AdoptOpenJDK/openjdk`

`brew cask install adoptopenjdk11`


### Running the Client
1. Install dependencies: `cd client; npm install; cd ..`
2. Open VSCode on the `client` folder.
3. Press Ctrl+Shift+B resp. Cmd+Shift+B to compile the client.
4. Open the 'Run and Debug' view container and run the 'Launch Client' task.
   This will automatically start the server according to the Gobra-IDE settings.
   To use the server that has been assembled in the [previous step](#assemble-the-server), configure Gobra-IDE as explained in [Configuring Gobra Tools](#configuring-gobra-tools).

Note: `npm` / node has to be installed.


## Configuring Gobra Tools
Gobra Tools collectively represent the client's dependecies.
In particular, Gobra Tools consist of the server (called Gobra Server), Z3, and Boogie.
The Gobra Tools are bundled with the extension, i.e., no additional installation step is necessary.
The versions of Z3 and Boogie that get bundled are pinned in `client/z3-version` and `client/boogie-version`, and the bundled Gobra Server is built from the sources in this repository.

Gobra-IDE supports two modes to locate the Gobra Tools, which can be configured by setting `gobraSettings.buildVersion` in the extension's settings to `BuiltIn` (default) or `External`.

### Build version `BuiltIn`
`BuiltIn` uses the Gobra Tools that are bundled with the extension, which are located in the `dependencies/GobraTools` folder within the extension's installation directory.

### Build version `External`
Alternatively, `External` allows you to fully customize which dependencies the IDE is using.
The following settings (and default values) are taken into account when using build version `External`:
```
"gobraDependencies.gobraToolsPaths": {
   "gobraToolsBasePath": {
      "windows": "%APPDATA%\\Roaming\\Code\\User\\globalStorage\\viper-admin.gobra-ide\\Local\\GobraTools",
      "linux": "$HOME/.config/Code/User/globalStorage/viper-admin.gobra-ide/Local/GobraTools",
      "mac": "$HOME/Library/Application Support/Code/User/globalStorage/viper-admin.gobra-ide/Local/GobraTools"
   },
   "z3Executable": {
      "windows": "$gobraTools$\\z3\\bin\\z3.exe",
      "linux": "$gobraTools$/z3/bin/z3",
      "mac": "$gobraTools$/z3/bin/z3"
   },
   "boogieExecutable": {
      "windows": "$gobraTools$\\boogie\\Binaries\\Boogie.exe",
      "linux": "$gobraTools$/boogie/Binaries/Boogie",
      "mac": "$gobraTools$/boogie/Binaries/Boogie"
   },
   "serverJar": {
      "windows": "$gobraTools$\\server\\server.jar",
      "linux": "$gobraTools$/server/server.jar",
      "mac": "$gobraTools$/server/server.jar"
   }
}
```
`gobraDependencies.gobraToolsPaths.gobraToolsBasePath` configures the path that the IDE is using to locate the Gobra Tools. This path is used to substitute `$gobraTools$` in `gobraDependencies.gobraToolsPaths.z3Executable`, `gobraDependencies.gobraToolsPaths.boogieExecutable`, and `gobraDependencies.gobraToolsPaths.serverJar`.

For example, if you want to use the bundled Boogie and Z3 but use your own built server, you may use the following configuration (adapt the base path to the extension's actual installation directory).
```
"gobraSettings.buildVersion": "External",
"gobraDependencies.gobraToolsPaths": {
   "gobraToolsBasePath": {
      "mac": "$HOME/.vscode/extensions/viper-admin.gobra-ide-<version>-<platform>/dependencies/GobraTools"
   },
   "serverJar": {
      "mac": <path to JAR>
   }
}
```
Note that the extension and the Gobra Server check at startup that they implement the same communication protocol version. When using `External` tools, make sure that the server's version matches the extension's.


#### Debugging paths used by Gobra-IDE
In case it is unclear which paths Gobra-IDE is using to locate the server, Z3 or Boogie, Gobra-IDE provides useful output in Visual Studio Code > View > Output > Gobra-IDE (in the dropdown menu).
Before launching the server, Gobra-IDE first locates your Java installation, tries to run `<path to java> -version` followed by running `<path to z3> --version`.
Typical output (shortened) looks as follows, where `< ... >` is used to annotate or omit parts of the output.

```
Locating the Gobra tools for build version BuiltIn < indicates which build version Gobra-IDE is using >
Checking Java...
Searching for Java home...
Using Java home {
  "path": "/opt/homebrew/Cellar/openjdk/23.0.1/libexec/openjdk.jdk/Contents/Home",
  < further details about the located Java home >
  }
}
Gobra IDE: Running '/opt/homebrew/Cellar/openjdk/23.0.1/libexec/openjdk.jdk/Contents/Home/bin/java -version'
┌──── Begin stdout ────┐

└──── End stdout ──────┘
┌──── Begin stderr ────┐
openjdk version "23.0.1" 2024-10-15
< further version information >

└──── End stderr ──────┘
Checking Z3...
Gobra IDE: Running '<extension installation directory>/dependencies/GobraTools/z3/bin/z3 --version'
┌──── Begin stdout ────┐
Z3 version 4.8.6 - 64 bit

└──── End stdout ──────┘
┌──── Begin stderr ────┐

└──── End stderr ──────┘
Gobra IDE: Running '"/opt/homebrew/Cellar/openjdk/23.0.1/libexec/openjdk.jdk/Contents/Home/bin/java" -Xss128m -jar < omitted path to the server JAR> --logLevel TRACE' (relative to < omitted path used as working directory >)
```


## Locally checking license headers:
Run `npx github:viperproject/check-license-header#v1 check --config .github/license-check/config.json --strict` in the repository's root directory to check whether all files adhere to the license configuration


## Release Management
A nightly release is created daily at 7:00 UTC.
Stable releases and pre-releases should be created as follows (manually triggered nightly releases can be created similarly as well):
1. Open [test workflow on GitHub.com](https://github.com/viperproject/gobra-ide/actions?query=workflow%3Atest)
2. Click on "Run workflow"
3. Choose the branch, type `stable` or `pre-release` (`nightly` for manually creating a nightly release), a tag name (e.g. `v1.0-beta.1`), and a release name (this will become the release's title). Mind the version convention below when choosing the type.

Type `stable` will create a draft release with the chosen tag name (the tag itself will be created when publishing the release) and release name.
The release body will consist of the commit hashes and versions of the dependencies.
In addition, the release assets (the platform-specific extensions and Gobra tools) will be created and attached to the release.
Once the workflow has completed, the extension is published to the Visual Studio Marketplace and the Open VSX Registry if the version in `client/package.json` differs from the latest published one.

Type `pre-release` behaves like `stable` but publishes the extension to the marketplaces as a pre-release version, which users have to opt into ("Switch to Pre-Release Version").
**Version convention: pre-releases must use an odd minor version (e.g. `3.1.x`, `3.3.x`) and stable releases an even one (e.g. `3.0.x`, `3.2.x`).**
This is necessary because the marketplaces do not support semver pre-release suffixes and always serve users the highest version number of the respective channel.

In case the type `nightly` was selected, a GitHub pre-release is created (no marketplace publishing).
Note that in this case the tag name and release name will be ignored.

Alternatively, the manual triggering of the workflow can be done via command line:
```
curl -X POST -u <username>:<token> -H "Accept: application/vnd.github.v3+json" "https://api.github.com/repos/viperproject/gobra-ide/actions/workflows/test.yml/dispatches" -d '{"ref":"<branch name>", "type":"stable", "tag_name": "v1.0-beta.1", "release_name": "1.0 Beta 1"}'
```
