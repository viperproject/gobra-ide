# Visual Studio Code extension for Gobra

[![Test Status](https://github.com/viperproject/gobra-ide/workflows/test/badge.svg?branch=master)](https://github.com/viperproject/gobra-ide/actions?query=workflow%3Atest+branch%3Amaster)
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
3. Assemble the binary: `sbt assembly`
4. Navigate back to Gobra-IDE directory: `cd ..`

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
