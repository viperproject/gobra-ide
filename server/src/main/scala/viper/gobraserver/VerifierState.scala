package viper.gobraserver

import viper.gobra.Gobra
import viper.gobra.frontend.Config
import viper.gobra.reporting.VerifierResult

import org.eclipse.lsp4j.{
    Diagnostic,
    Position,
    Range,
    DiagnosticSeverity,
    PublishDiagnosticsParams
}
import collection.JavaConverters._

import org.eclipse.lsp4j.services.LanguageClient


class VerifierState {
    private val verifier: Gobra = new Gobra()
    private var diagnostics: List[Diagnostic] = Nil
    private var client: Option[LanguageClient] = None

    def verify(config: VerifierConfig): VerificationResult = {

        println("\nVerifying File\n")

        /*
         * When gobra can accept just a normal config which is not constructed with command
         * line arguments, change this to just accept the config as an input to the jsonrequest
         * and verify with this config. No need to store the config anywhere.
         */

        val result = verifier.verify(new Config(Seq("--input", config.fileData.filePath)))

        result match {
            case VerifierResult.Success => {
                this.resetDiagnostics()
            }
            case VerifierResult.Failure(errors) => {
                this.resetDiagnostics()
                for (error <- errors) {
                    //fill diagnostic list from errors
                    val startPos = new Position(error.position.start.line-1, error.position.start.column-1) /* why is off by 1? */
                    val endPos =
                        error.position.end match {
                            case Some(pos) => new Position(pos.line-1, pos.column-1)
                            case None      => startPos
                        }
                    val diagnostic = new Diagnostic(new Range(startPos, endPos), error.message, DiagnosticSeverity.Error, "")

                    this.addDiagnostic(diagnostic)
                }
            }
        }
        this.publishDiagnostics(config.fileData.fileUri)

        result match {
            case VerifierResult.Success => new VerificationResult(true, "")
            case VerifierResult.Failure(errors) => new VerificationResult(false, errors.head.id)
        }
    }

    def publishDiagnostics(fileUri: String): Unit = {
        this.client match {
            case Some(c) =>
                val params = new PublishDiagnosticsParams(fileUri, this.diagnostics.asJava)
                c.publishDiagnostics(params)
            case _ =>
        }
    }

    def getDiagnostics(): java.util.List[Diagnostic] = {
        this.diagnostics.asJava
    }


    def addDiagnostic(diagnostic: Diagnostic): Unit = {
        this.diagnostics ::= diagnostic
    }

    def resetDiagnostics(): Unit = {
        this.diagnostics = Nil
    }

    def setClient(client: LanguageClient): Unit = {
        this.client = Some(client)
    }

    def getClient(): Option[LanguageClient] = {
        this.client
    }

}
