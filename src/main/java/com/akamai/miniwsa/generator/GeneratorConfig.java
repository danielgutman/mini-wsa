package com.akamai.miniwsa.generator;

import java.time.Duration;
import java.time.Instant;

/**
 * Settings for {@link SecurityEventGenerator}.
 *
 * @param totalEvents approximate number of events to produce (waves count toward it)
 * @param configId    the configId stamped on generated events
 * @param waveCount   number of "attack waves" (bursts from one IP on one path)
 * @param waveSize    events per wave
 * @param startTime   start of the generated time window
 * @param span        length of the time window background events spread across
 */
public record GeneratorConfig(
        int totalEvents,
        long configId,
        int waveCount,
        int waveSize,
        Instant startTime,
        Duration span
) {

    public static GeneratorConfig defaults() {
        return new GeneratorConfig(
                1000, 14227L, 3, 50,
                Instant.parse("2026-05-20T00:00:00Z"), Duration.ofHours(24));
    }
}
