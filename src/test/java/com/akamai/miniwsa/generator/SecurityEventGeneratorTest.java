package com.akamai.miniwsa.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.domain.model.SecurityEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityEventGenerator}: it produces the requested volume, the
 * output is reproducible for a given seed, and attack waves are genuine bursts (many events
 * from one IP against one path within a short window).
 */
class SecurityEventGeneratorTest {

    private static final GeneratorConfig CONFIG = new GeneratorConfig(
            200, 14227L, 2, 50, Instant.parse("2026-05-20T00:00:00Z"), Duration.ofHours(24));

    @Test
    void producesAtLeastTheRequestedVolumeWithRequiredFields() {
        List<SecurityEvent> events = new SecurityEventGenerator(1L).generate(CONFIG);

        assertThat(events).hasSizeGreaterThanOrEqualTo(CONFIG.totalEvents());
        assertThat(events).allSatisfy(event -> {
            assertThat(event.eventId()).isNotBlank();
            assertThat(event.configId()).isEqualTo(14227L);
            assertThat(event.rule().category()).isNotNull();
            assertThat(event.action()).isNotNull();
            assertThat(event.clientIp()).isNotBlank();
        });
    }

    @Test
    void isReproducibleForTheSameSeed() {
        List<SecurityEvent> first = new SecurityEventGenerator(99L).generate(CONFIG);
        List<SecurityEvent> second = new SecurityEventGenerator(99L).generate(CONFIG);

        assertThat(first).extracting(SecurityEvent::eventId)
                .isEqualTo(second.stream().map(SecurityEvent::eventId).toList());
    }

    @Test
    void attackWavesAreBurstsFromOneIpOnOnePath() {
        List<SecurityEvent> events = new SecurityEventGenerator(7L).generate(CONFIG);

        // Group by (clientIp, path); at least one group should be a wave-sized burst.
        Map<String, List<SecurityEvent>> byIpAndPath = events.stream()
                .collect(Collectors.groupingBy(e -> e.clientIp() + "|" + e.path()));

        List<SecurityEvent> biggestBurst = byIpAndPath.values().stream()
                .max((a, b) -> Integer.compare(a.size(), b.size()))
                .orElseThrow();

        assertThat(biggestBurst).hasSizeGreaterThanOrEqualTo(CONFIG.waveSize());
        // All events in the burst share IP + path and fall within a few minutes.
        Instant min = biggestBurst.stream().map(SecurityEvent::timestamp).min(Instant::compareTo).orElseThrow();
        Instant max = biggestBurst.stream().map(SecurityEvent::timestamp).max(Instant::compareTo).orElseThrow();
        assertThat(Duration.between(min, max)).isLessThanOrEqualTo(Duration.ofMinutes(3));
    }
}
