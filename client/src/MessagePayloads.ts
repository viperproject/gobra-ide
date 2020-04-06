import { Helper } from "./Helper";


export class FileData {
    filePath: string;
    fileUri: string;

    constructor() {
        this.filePath = Helper.getFilePath();
        this.fileUri = Helper.getFileUri();
    }
}

export class VerifierConfig {
    fileData: FileData;

    constructor() {
        this.fileData = new FileData();
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