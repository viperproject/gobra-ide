package viper.gobraserver

import org.eclipse.lsp4j.Position

case class FileData (
  filePath: String,
  fileUri: String
)

case class GobraSettings (
  backend: String,
  serverMode: Boolean,
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
  gobraSettings: GobraSettings,
  z3Executable: String,
  boogieExecutable: String
)

case class OverallVerificationResult(
  fileUri: String,
  success: Boolean,
  message: String
)

case class PreviewData (
  fileData: FileData,
  internalPreview: Boolean,
  viperPreview: Boolean,
  selections: Array[Array[Position]]
)

case class HighlightingPosition(
  startIndex: Int,
  length: Int
)