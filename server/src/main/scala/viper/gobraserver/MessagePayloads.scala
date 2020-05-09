package viper.gobraserver

import org.eclipse.lsp4j.Position

case class FileData (
  filePath: String,
  fileUri: String
)

case class ClientConfig (
  backend: String,
  serverMode: Boolean,
  debug: Boolean,
  eraseGhost: Boolean,
  unparse: Boolean,
  printInternal: Boolean,
  printViper: Boolean,
  parseOnly: Boolean,
  logLevel: String
)

case class VerifierConfig (
  fileData: FileData,
  clientConfig: ClientConfig
)

case class OverallVerificationResult(
  success: Boolean,
  message: String
)

case class ChangeRange (
  startPos: Position,
  endPos: Position,
  text: String
)

case class FileChanges (
  fileUri: String,
  ranges: Array[ChangeRange]
)