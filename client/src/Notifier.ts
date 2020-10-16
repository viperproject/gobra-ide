// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import { Helper } from "./Helper";

// all credits go to prusti-assistant

type Listener = () => void;

const oneTimeListeners: Map<Event, Listener[]> = new Map();

export class Notifier {

    /**
     * Register a **one-time** listener
     */
    public static register(event: Event, listener: Listener) {
        let listeners = oneTimeListeners.get(event);
        if (!listeners) {
            listeners = [];
            oneTimeListeners.set(event, listeners);
        }
        listeners.push(listener);
    }

    /**
     * Wait for a particular event.
     */
    public static wait(event: Event): Promise<void> {
        return new Promise(resolve => {
            Notifier.register(event, resolve);
        });
    }

    public static notify(event: Event) {
        Helper.log(`Notify event: ${Event[event]}`);
        const listeners = oneTimeListeners.get(event);
        oneTimeListeners.delete(event);
        if (listeners) {
            listeners.forEach(listener => listener());
        }
    }
}

export enum Event {
    EndExtensionActivation
}
