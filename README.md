# Visual Studio Code extension for Gobra
https://github.com/viperproject/gobra-ide/actions/workflows/test.yml/badge.svg?branch=master
[![Test Status](https://github.com/viperproject/gobra-ide/actions/workflows/test.yml/badge.svg?branch=master)](https://github.com/viperproject/gobra-ide/actions?query=workflow%3Atest+branch%3Amaster)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL%202.0-brightgreen.svg)](./LICENSE)

This Visual Studio Code extension allows interactive verification using Gobra.  
Additionally, other visual features such as inspecting the intermediate
translation of Gobra are available.


## Installation Instructions

### Assemble the server:
1. Navigate to the server folder: `cd server`
2. Create a symlink to your local Gobra checkout:  
   Windows: `mklink /D .\gobra \path\to\gobra`  
   Mac: `ln -s /path/to/gobra ./gobra`
3. Create a symlink to your local ViperServer checkout:  
   Windows: `mklink /D .\viperserver \path\to\viperserver`  
   Mac: `ln -s /path/to/viperserver ./viperserver`
4. Assemble the binary: `sbt assembly`
5. Navigate back to Gobra-IDE directory: `cd ..`

Note: `sbt` has to be installed, which in turn requires a JDK.
`adoptopenjdk13` is known to be not compatible with currently in
use sbt version.
JDK version 11 is working (assuming [Homebrew](https://brew.sh) is used):

`brew tap AdoptOpenJDK/openjdk`

`brew cask install adoptopenjdk11`

### Running the Client
1. Install dependencies: `cd client; npm install; cd ..`
2. Open VSCode on the `client` folder.
3. Press Ctrl+Shift+B resp. Cmd+Shift+B to compile the client.
4. Open the 'Run and Debug' view container and run the 'Launch Client' task.
    This will automatically start the server as well

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
