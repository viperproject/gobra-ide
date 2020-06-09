package viper.gobraserver

import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification


trait IdeLanguageClient extends LanguageClient {
  @JsonNotification("gobraServer/overallResultNotification")
  def overallResultNotification(params: String): Unit

  @JsonNotification("gobraServer/finishedVerification")
  def finishedVerification(fileUri: String): Unit

  @JsonNotification("gobraServer/noVerificationResult")
  def noVerificationResult(): Unit

  @JsonNotification("gobraServer/verificationException")
  def verificationException(fileUri: String): Unit

  @JsonNotification("gobraServer/finishedGoifying")
  def finishedGoifying(fileUri: String, success: Boolean): Unit

  @JsonNotification("gobraServer/finishedGobrafying")
  def finishedGobrafying(oldFilePath: String, newFilePath: String): Unit

}