import { Helper } from "./Helper";


export class VerifierConfig {
    filePath: string;
    fileUri: string;

    constructor() {
        this.filePath = Helper.getFilePath();
        this.fileUri = Helper.getFileUri();
    }
}

export class VerificationResult {
    success: boolean;
    error: string;

    constructor(success: boolean, error: string) {
        this.success = success;
        this.error = error;
    }
    
}