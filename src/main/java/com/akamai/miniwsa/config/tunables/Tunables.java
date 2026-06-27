package com.akamai.miniwsa.config.tunables;

import com.akamai.miniwsa.domain.service.ScoringWeights;
import java.time.Duration;

/**
 * An immutable snapshot of the runtime-tunable settings. Held in an {@link java.util.concurrent.atomic.AtomicReference}
 * by {@link TunablesHolder} and read per request, so swapping it changes behaviour live without
 * a restart. Consumers take one snapshot per operation for a consistent view.
 */
public record Tunables(
        ScoringWeights scoring,
        Duration repeatOffenderWindow,
        long repeatOffenderThreshold
) {
}
