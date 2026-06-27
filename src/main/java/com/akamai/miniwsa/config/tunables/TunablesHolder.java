package com.akamai.miniwsa.config.tunables;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current {@link Tunables} behind an {@link AtomicReference}. Reads ({@link #current()})
 * are lock-free and always return a consistent snapshot; {@link #replaceWith(Tunables)} swaps the
 * whole snapshot atomically, so a configuration change takes effect immediately for subsequent
 * requests without restarting or pausing the application.
 */
public class TunablesHolder {

    private final AtomicReference<Tunables> current;

    public TunablesHolder(Tunables initial) {
        this.current = new AtomicReference<>(initial);
    }

    public Tunables current() {
        return current.get();
    }

    public void replaceWith(Tunables tunables) {
        current.set(tunables);
    }
}
