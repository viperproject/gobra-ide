// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

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
  logLevel: String,
  moduleName: String,
  includeDirs: Array[String]
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