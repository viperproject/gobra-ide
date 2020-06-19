import * as path from 'path';
import * as Mocha from 'mocha';
import * as glob from 'glob';

// copied from vscode tutorial testing extensions (except file names)
export function run(): Promise<void> {
  // Create the mocha test
  const mocha = new Mocha({
    ui: 'tdd',
    timeout: '1m'
  });
  mocha.useColors(true);

  const evaluateRoot = path.resolve(__dirname, '..');

  return new Promise((c, e) => {
    glob('**/**.evaluate.js', { cwd: evaluateRoot }, (err, files) => {
      if (err) {
        return e(err);
      }

      // Add files to the evaluation suite
      files.forEach(f => mocha.addFile(path.resolve(evaluateRoot, f)));

      try {
        // Run the mocha test to evaluate the extension.
        mocha.run(failures => {
          if (failures > 0) {
            e(new Error(`${failures} evaluatios failed.`));
          } else {
            c();
          }
        });
      } catch (err) {
        e(err);
      }
    });
  });
}