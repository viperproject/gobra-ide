package viper.gobraserver

import viper.gobra.GobraFrontend
import viper.gobra.reporting.VerifierResult

import org.eclipse.lsp4j.{
    Diagnostic,
    Position,
    Range,
    DiagnosticSeverity,
    PublishDiagnosticsParams
}

import scala.collection.mutable.ListBuffer
import collection.JavaConverters._

import org.eclipse.lsp4j.services.LanguageClient


object VerifierState extends GobraFrontend {
    private var diagnostics: ListBuffer[Diagnostic] = ListBuffer.empty[Diagnostic]
    private var client: Option[LanguageClient] = None

    def verify(verifierConfig: VerifierConfig): VerificationResult = {
        val config = Helper.verifierConfigToConfig(verifierConfig)
        val verifier = createVerifier(config)
        val result = verifier.verify(config)

        result match {
            case VerifierResult.Success => {
                this.resetDiagnostics()
            }
            case VerifierResult.Failure(errors) => {
                this.resetDiagnostics()
                for (error <- errors) {
                    //fill diagnostic list from errors
                    val startPos = new Position(error.position.start.line-1, error.position.start.column-1) /* why is off by 1? */
                    val endPos = error.position.end match {
                        case Some(pos) => new Position(pos.line-1, pos.column-1)
                        case None      => startPos
                    }
                    val diagnostic = new Diagnostic(new Range(startPos, endPos), error.message, DiagnosticSeverity.Error, "")

                    this.addDiagnostic(diagnostic)
                }
            }
        }
        this.publishDiagnostics(verifierConfig.fileData.fileUri)

        result match {
            case VerifierResult.Success => new VerificationResult(true, "")
            case VerifierResult.Failure(errors) => new VerificationResult(false, errors.head.id)
        }
    }

    def publishDiagnostics(fileUri: String): Unit = {
        this.client match {
            case Some(c) =>
                val params = new PublishDiagnosticsParams(fileUri, getDiagnostics())
                c.publishDiagnostics(params)
            case _ =>
        }
    }

    def getDiagnostics(): java.util.List[Diagnostic] = {
        this.diagnostics.asJava
    }


    def addDiagnostic(diagnostic: Diagnostic): Unit = {
        this.diagnostics += diagnostic
    }

    def resetDiagnostics(): Unit = {
        this.diagnostics = ListBuffer.empty[Diagnostic]
    }

    def setClient(client: LanguageClient): Unit = {
        this.client = Some(client)
    }

    def getClient(): Option[LanguageClient] = {
        this.client
    }

}
