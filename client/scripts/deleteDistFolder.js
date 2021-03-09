// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

const fs = require('fs');

// this script assumes that it is invoked in the parent folder, i.e. 'client'
const dir = 'dist';
try {
    fs.rmSync(dir, { recursive: true, force: true });
} catch (err) {
    console.error(`Error while deleting folder '${dir}'`, err);
}
