package viper.gobraserver

class VerifierConfig {
    var filePath: String = ""
    var fileUri: String = ""
}

class VerificationResult(
    var success: Boolean,
    var error: String
)