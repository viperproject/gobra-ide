// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

case class FileData (
  fileUri: String
)

case class IsolationData (
  fileUri: String,
  lineNrs: Array[Int]
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
  fileData: Array[FileData],
  isolate: Array[IsolationData],
  gobraSettings: GobraSettings,
  z3Executable: String,
  boogieExecutable: String
)

case class OverallVerificationResult(
  fileUris: Array[String],
  success: Boolean,
  message: String,
  members: Array[MemberInformation] // information about verified members. Empty if entire program has been verified
)

case class MemberInformation(
  isUnknown: Boolean, // set if only a particular member has been verified but no additional information is available. If set, all other fields should be ignored
  fileUri: String,
  success: Boolean,
  range: Range
)

case class PreviewData (
  fileData: Array[FileData],
  internalPreview: Boolean,
  viperPreview: Boolean,
  selections: Array[Array[Position]]
)

case class HighlightingPosition(
  startIndex: Int,
  length: Int
)