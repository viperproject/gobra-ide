<img src="images/gobra.png" height="250">

Gobra IDE directly integrates verification with Gobra into VSCode.

## First Usage
1. Install the [requirements](#requirements)
2. Install [this extension](https://marketplace.visualstudio.com/items?itemName=viper-admin.gobra-ide) from the Visual Studio Marketplace
3. Open a Gobra or Go file to activate this extension. At first activation, this extension will automatically download Gobra.

Opening or modifying a Gobra program (file extension `.gobra`) will automatically trigger its verification.
In addition, a verification can manually be started in the command palette (View -> Command Palette, or Shift+Ctrl+P on Ubuntu) by running the `Gobra: Verify currently opened File` command.
You should see a "Verification of ..." message in the status bar while Gobra is running. 
The result of the verification is then reported in the status bar and in the "Problems" tab (open it with View -> Problems).

To update Gobra, run the command `Gobra: Update Gobra Tools` in the command palette.

## Features
### Verification of Go programs
A Go program (file extension `.go`) can be annotated with specification. 
The specification has to be located either in comments starting with `//@` or starting and ending with `/*@` resp. `@*/`. 
Verification of Go programs is supported by this extension as well.
Note however that automatic verification is not supported for these programs and verification has to manually be invoked in the command palette.

A Gobra program can be converted to a Go program and back by running the `Gobra: Goify currently opened File` resp. `Gobra: Gobrafy currently opened File`.

### Verification Result Caching
This extension caches verification results by default.
The cache can be flushed by running the `Gobra: Flush Viper Server Cache` command from the command palette.

### Show Intermediate Representations
Right clicking on some selected code in a Gobra program reveals two actions "Show Internal Representation Preview" and "Show Viper Code Preview".
Both result in a preview on the right hand side of the internal representation used by Gobra resp. the resulting code in the Viper intermediate language.
Marked in green are the translated parts of the selected code.

## Requirements
- [Java Runtime Environment (or JDK), 64 bit, version 1.8 or later](https://www.java.com/en/download/)
