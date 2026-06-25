package com.akamai.miniwsa.application.ports;

import java.time.Instant;

/**
 * Provides the current time as an explicit, injectable effect.
 *
 * <p>Business logic must not call {@code Instant.now()} directly; reading the
 * clock is an effect (IO policy §5). Depending on this port keeps the ingestion
 * service deterministic and unit-testable with a fixed clock.
 */
public interface ClockProvider {
    Instant now();
}
