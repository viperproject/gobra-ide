// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2021 ETH Zurich.

declare module 'nyc' {
    class NYC {
      constructor(config?: unknown);
      createTempDirectory(): Promise<void>;
      writeCoverageFile(): Promise<void>;
      wrap(): Promise<void>;
    }
    export = NYC;
}
