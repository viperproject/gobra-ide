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
Gobra-IDE supports three modes to fetch the Gobra Tools, which can be configured by setting `gobraSettings.buildVersion` in the extension's settings to `Stable` (default), `Nightly` or `Local`.

### Build versions `Stable` and `Nightly`
`Stable` and `Nightly` use the latest non-prerelease and latest release, resp., of [Gobra-IDE on GitHub](https://github.com/viperproject/gobra-ide/releases).
More specifically, the platform-specific `GobraTools<Linux|Mac|Win>.zip` is downloaded, unzipped, and stored in the following directory in the case of `Stable` (otherwise, replace `Stable` by `Nightly` in the following paths).
- Linux: `$HOME/.config/Code/User/globalStorage/viper-admin.gobra-ide/Stable/GobraTools`
- macOS: `$HOME/Library/Application Support/Code/User/globalStorage/viper-admin.gobra-ide/Stable/GobraTools`
- Windows: `%APPDATA%\Roaming\Code\User\globalStorage\viper-admin.gobra-ide\Stable\GobraTools`

Note that Gobra-IDE tries to download the Gobra Tools only if no Gobra Tools can be found locally in the respective folder and only after asking the user to download them.
This ensures that the Gobra Tools are not unknowingly replaced by a newer version.
To update the Gobra Tools for the currently configured build version, press Ctrl+Shift+P resp. Cmd+Shift+P and select "Gobra: Update Gobra Tools".


### Build version `Local`
Alternatively, `Local` allows you to fully customize which dependencies the IDE is using.
The following settings (and default values) are taken into account when using build version `Local`:
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

For example, if you want to use Boogie and Z3 from the latest `Nightly` release but use your own built server, you may use the following configuration.
This configures Gobra-IDE to use the nightly GobraTools but overwrites the path to the server's JAR file.
```
"gobraDependencies.gobraToolsPaths": {
   "gobraToolsBasePath": {
      "windows": "%APPDATA%\\Roaming\\Code\\User\\globalStorage\\viper-admin.gobra-ide\\Nightly\\GobraTools",
      "linux": "$HOME/.config/Code/User/globalStorage/viper-admin.gobra-ide/Nightly/GobraTools",
      "mac": "$HOME/Library/Application Support/Code/User/globalStorage/viper-admin.gobra-ide/Nightly/GobraTools"
   },
   "serverJar": {
      "windows": <path to JAR>,
      "linux": <path to JAR>,
      "mac": <path to JAR>
   }
}
```


#### Debugging paths used by Gobra-IDE
In case it is unclear which paths Gobra-IDE is using to locate the server, Z3 or Boogie, Gobra-IDE provides useful output in Visual Studio Code > View > Output > Gobra-IDE (in the dropdown menu).
Before launching the server, Gobra-IDE first locates your Java installation, tries to run `<path to java> -version` followed by running `<path to z3> --version`.
Typical output (shortened) looks as follows, where `< ... >` is used to annotate or omit parts of the output.

```
Ensuring dependencies for build channel Stable < indicates which build version Gobra-IDE is using >
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
Gobra IDE: Running '/Users/arquintlinard/Library/Application Support/Code/User/globalStorage/viper-admin.gobra-ide/Stable/GobraTools/z3/bin/z3 --version'
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
Stable releases should be created as follows (manually triggered nightly releases can be created similarly as well):
1. Open [test workflow on GitHub.com](https://github.com/viperproject/gobra-ide/actions?query=workflow%3Atest)
2. Click on "Run workflow"
3. Choose `master` branch, type `stable` (`nightly` for manually creating a nightly release), a tag name (e.g. `v1.0-beta.1`), and a release name (this will become the release's title).

Type `stable` will create a draft release with the chosen tag name (the tag itself will be created when publishing the release) and release name.
The release body will consist of the commit hashes of the depending repositories.
In addition, the release assets will be created and attached to the release.
Please wait until the workflow has completed before publishing the release because Gobra IDE will use the assets of the latest published release.

In case the type `nightly` was selected, the same steps will be performed as done for the periodic nightly releases.
Note that in this case the tag name and release name will be ignored.

Alternatively, the manual triggering of the workflow can be done via command line:
```
curl -X POST -u <username>:<token> -H "Accept: application/vnd.github.v3+json" "https://api.github.com/repos/viperproject/gobra-ide/actions/workflows/test.yml/dispatches" -d '{"ref":"<branch name>", "type":"stable", "tag_name": "v1.0-beta.1", "release_name": "1.0 Beta 1"}'
```
