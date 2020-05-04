package viper.gobraserver

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

case class VerificationResult(
  var success: Boolean,
  var error: String
)