package viper.gobraserver

import org.eclipse.lsp4j.Position

case class FileData (
  filePath: String,
  fileUri: String
)

case class GobraSettings (
  backend: String,
  serverMode: Boolean,
  z3Exe: String,
  boogieExe: String,
  debug: Boolean,
  eraseGhost: Boolean,
  goify: Boolean,
  unparse: Boolean,
  printInternal: Boolean,
  printViper: Boolean,
  parseOnly: Boolean,
  logLevel: String
)

case class VerifierConfig (
  fileData: FileData,
  gobraSettings: GobraSettings
)

case class OverallVerificationResult(
  success: Boolean,
  message: String
)