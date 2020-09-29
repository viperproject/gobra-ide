// this file is taken from the helloworld-test-sample from https://github.com/microsoft/vscode-extension-samples

// Copyright (c) Microsoft Corporation
//
// All rights reserved. 
//
// MIT License
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation 
// files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
// modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software 
// is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
// OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS 
// BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT 
// OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

import * as path from 'path';

import { runTests } from 'vscode-test';

async function main(): Promise<number> {
	try {
		// The folder containing the Extension Manifest package.json
		// Passed to `--extensionDevelopmentPath`
		const extensionDevelopmentPath = path.resolve(__dirname, '../../');

		// The path to the extension test script
		// Passed to --extensionTestsPath
		const extensionTestsPath = path.resolve(__dirname, 'index.js');

		const testOption = { 
			extensionDevelopmentPath: extensionDevelopmentPath, 
			extensionTestsPath: extensionTestsPath 
		};
		// Download VS Code, unzip it and run the integration test
		const res = await runTests(testOption);
		return Promise.resolve(res);
	} catch (err) {
        console.error(err);
		console.error('Failed to run tests');
		return Promise.resolve(-1);
	}
}

main()
	.then((exitCode: number) => {
		console.log(`main function has ended, exitCode: ${exitCode}`);
		process.exit(exitCode);
	});
