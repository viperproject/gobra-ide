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
import * as Mocha from 'mocha';
import * as glob from 'glob';
import NYC = require("nyc");

export async function run(): Promise<void> {
	const nyc: NYC = new NYC({
        cwd: path.join(__dirname, "..", ".."),
        instrument: true,
        extension: [
            ".ts",
            ".tsx"
        ],
        exclude: [
            "**/*.d.ts",
            "**/.vscode-test/**"
        ],
        all: true,
        hookRequire: true,
        hookRunInContext: true,
        hookRunInThisContext: true,
    });
    await nyc.createTempDirectory();
    await nyc.wrap();

	// Create the mocha test
	const mocha = new Mocha({
		ui: 'tdd',
		// Installing and starting Gobra might take some minutes
        timeout: 600_000, // ms
        color: true,
	});

	const testsRoot = path.resolve(__dirname, '..');

	const files: Array<string> = await new Promise((resolve, reject) =>
        glob(
            "**/*.test.js",
            {
                cwd: testsRoot,
            },
            (err, result) => {
                if (err) reject(err)
                else resolve(result)
            }
        )
    )

	// Add files to the test suite
    files.forEach(f => mocha.addFile(path.resolve(testsRoot, f)));

    const failures: number = await new Promise(resolve => mocha.run(resolve));
    await nyc.writeCoverageFile()

    if (failures > 0) {
        throw new Error(`${failures} tests failed.`)
    }
}
