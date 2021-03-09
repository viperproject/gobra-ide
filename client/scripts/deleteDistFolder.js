const fs = require('fs');

// this script assumes that it is invoked in the parent folder, i.e. 'client'
const dir = 'dist';
try {
    fs.rmSync(dir, { recursive: true, force: true });
} catch (err) {
    console.error(`Error while deleting folder '${dir}'`, err);
}
