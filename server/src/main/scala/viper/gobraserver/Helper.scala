package viper.gobraserver

import viper.gobra.frontend.Config
import viper.gobra.backend.ViperBackends

import java.io.File

import ch.qos.logback.classic.Level

object Helper {
    def verifierConfigToConfig(verifierConfig: VerifierConfig): Config = {
        verifierConfig match {
            case VerifierConfig(
                FileData(path, _),
                ClientConfig(backendId, debug, eraseGhost, unparse, printInternal, printViper, parseOnly, logLevel)
            ) => {
                val backend = backendId match {
                    case "SILICON" => ViperBackends.SiliconBackend
                    case "CARBON" => ViperBackends.CarbonBackend
                    case _ => ViperBackends.SiliconBackend
                }

                Config(
                    inputFile = new File(path),
                    backend = backend,
                    logLevel = Level.toLevel(logLevel),
                    unparse = unparse,
                    debug = debug,
                    eraseGhost = eraseGhost,
                    printInternal = printInternal,
                    printVpr = printViper
                )
            }
        }
    }
}