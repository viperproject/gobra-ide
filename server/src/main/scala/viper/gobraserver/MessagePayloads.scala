package viper.gobraserver

class FileData {
    var filePath: String = ""
    var fileUri: String = ""
}

class VerifierConfig {
    var fileData: FileData = null
}

class VerificationResult(
    var success: Boolean,
    var error: String
)