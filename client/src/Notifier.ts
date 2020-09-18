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
    public static wait(event: Event) {
        return new Promise(resolve => {
            Notifier.register(event, resolve);
        });
    }

    public static notify(event: Event) {
        console.log(`Notify event: ${Event[event]}`);
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
