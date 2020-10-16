# Gobra IDE Build System
Gobra IDE uses webpack as well as compilation using the plain typescript transpiler:
- Webpack is used to bundle all transpiled source files into one large javascript file `dist/extension.js`.
This is done before running the extension or packaging the extension (using `vsce`).
The main motivation stems from `vsce` as it complains when including too many javascript files in the final `.vsix` package.
`vsce` suggests bundling the extension by following [this guide](https://code.visualstudio.com/api/working-with-extensions/bundling-extension).
- The plain webpack transpiler (`tsc`) on the other hand is used to compile the extension tests.
It doesn't make much sense to bundle the transpiled tests as additional entry points would need to be handled.

The `main` field in package.json configures the extension's entry point.
Note that this field is common to running the extension and running the tests and VSCode currently doesn't offer an option to overwrite this field when running tests (see [issue 85779](https://github.com/microsoft/vscode/issues/85779)).
To avoid hacky solutions such as modifying the `package.json` file on the fly (as suggested in the linked issue) the same destination directory (`dist/`) is used for webpack and the plain typescript transpiler (the directory is cleaned before invoking either compilation).


## Pitfalls during extension testing
Be aware of strange behavior during testing when using the following setup:
- Webpack is used to bundle the extension as `dist/extension.js`.
- `package.json` is configured accordingly to use the bundled extension.
- `tsc` is invoked to transpile all typescript files to `out/` and the extensionTestsPath is configured to be `out/test/index.js` i.e. run the tests.

In this particular setup, the tests will trigger the extension's activation and VSCode will start the extension from `dist/extension.js` (as configured in `package.json`).
However, the test files do not have access to the extension's global state as the tests import the extension files that are located in `out/` (instead of the actually run extension in `dist/`).
Hence, the tests will access global state of an extension (the one in `out/`) that is never started by VSCode.

There might be a workaround for this setup by not importing extension files into the tests, but use the following snippet and `extensionApi` to access the extension's public API:
```
const gobraExtension = vscode.extensions.getExtension("viper-admin.gobra-ide");
const extensionApi = await gobraExtension.activate()
```
