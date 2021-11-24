// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification


trait IdeLanguageClient extends LanguageClient {
  @JsonNotification("gobraServer/noVerificationInformation")
  def noVerificationInformation(): Unit

  @JsonNotification("gobraServer/overallResult")
  def overallResult(params: String): Unit

  @JsonNotification("gobraServer/verificationProgress")
  def verificationProgress(fileUri: String, progress: Int): Unit

  @JsonNotification("gobraServer/verificationException")
  /** note that `encodedFileUris` is a JSON array of strings each representing one file URI */
  def verificationException(encodedFileUris: String): Unit


  @JsonNotification("gobraServer/finishedGoifying")
  def finishedGoifying(fileUri: String, success: Boolean): Unit

  @JsonNotification("gobraServer/finishedGobrafying")
  def finishedGobrafying(oldFilePath: String, newFilePath: String, success: Boolean): Unit

  @JsonNotification("gobraServer/finishedViperCodePreview")
  def finishedViperCodePreview(ast: String, highlighted: String): Unit

  @JsonNotification("gobraServer/finishedInternalCodePreview")
  def finishedInternalCodePreview(internal: String, highlighted: String): Unit
}