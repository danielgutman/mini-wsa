package com.akamai.miniwsa.generator;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.model.GeoLocation;
import com.akamai.miniwsa.domain.model.Rule;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Produces realistic-looking {@link SecurityEvent}s for testing the pipeline, including
 * "attack waves" — bursts of events from one client IP against one sensitive path within a
 * few minutes (exercising top-attacker/top-path stats and the repeat-offender bonus).
 *
 * <p>Random generation is the generator's whole point, so it is isolated here (never in
 * enrichment logic, per the IO policy). Seeding the {@link Random} makes output reproducible.
 * The produced events serialize to the exact JSON the ingestion API accepts.
 */
public class SecurityEventGenerator {

    private static final Duration WAVE_DURATION = Duration.ofMinutes(3);

    private static final List<String> SENSITIVE_PATHS = List.of("/api/v1/login", "/admin", "/wp-login.php");
    private static final List<String> NORMAL_PATHS =
            List.of("/api/v1/search", "/api/v1/users", "/api/v1/orders", "/checkout", "/static/app.js", "/health");
    private static final List<String> HOSTS = List.of("www.example.com", "api.example.com", "shop.example.com");
    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE");
    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "curl/8.1.2",
            "python-requests/2.31.0", "Googlebot/2.1", "sqlmap/1.7");
    private static final List<String> COUNTRIES = List.of("US", "CN", "RU", "DE", "BR", "IN", "NL");

    private final Random random;

    public SecurityEventGenerator(long seed) {
        this.random = new Random(seed);
    }

    /** Generates the attack waves first, then fills the remainder with background traffic. */
    public List<SecurityEvent> generate(GeneratorConfig config) {
        List<SecurityEvent> events = new ArrayList<>();

        for (int w = 0; w < config.waveCount(); w++) {
            events.addAll(attackWave(config));
        }

        int remaining = Math.max(0, config.totalEvents() - events.size());
        for (int i = 0; i < remaining; i++) {
            events.add(backgroundEvent(config));
        }
        return events;
    }

    /** A burst from one IP hitting one sensitive path within {@link #WAVE_DURATION}. */
    private List<SecurityEvent> attackWave(GeneratorConfig config) {
        String clientIp = randomIp();
        String path = pick(SENSITIVE_PATHS);
        RuleCategory category = pick(List.of(RuleCategory.INJECTION, RuleCategory.BOT, RuleCategory.DOS));
        Instant waveStart = randomTimestamp(config);

        List<SecurityEvent> wave = new ArrayList<>(config.waveSize());
        for (int i = 0; i < config.waveSize(); i++) {
            Instant timestamp = waveStart.plusSeconds(random.nextInt((int) WAVE_DURATION.toSeconds()));
            wave.add(buildEvent(config, clientIp, path, timestamp, category,
                    pick(List.of(Severity.CRITICAL, Severity.HIGH)), Action.DENY));
        }
        return wave;
    }

    /** A single random background event spread across the configured window. */
    private SecurityEvent backgroundEvent(GeneratorConfig config) {
        boolean sensitive = random.nextInt(100) < 20;
        String path = sensitive ? pick(SENSITIVE_PATHS) : pick(NORMAL_PATHS);
        RuleCategory category = pick(List.of(RuleCategory.values()));
        Severity severity = pick(List.of(Severity.values()));
        Action action = pick(List.of(Action.values()));
        return buildEvent(config, randomIp(), path, randomTimestamp(config), category, severity, action);
    }

    private SecurityEvent buildEvent(GeneratorConfig config, String clientIp, String path, Instant timestamp,
                                     RuleCategory category, Severity severity, Action action) {
        Rule rule = new Rule(
                String.valueOf(950000 + random.nextInt(1000)),
                category.name(),
                category + " activity detected",
                severity,
                category);
        GeoLocation geo = new GeoLocation(pick(COUNTRIES), "city-" + random.nextInt(50));
        int statusCode = action == Action.DENY ? 403 : (random.nextBoolean() ? 200 : 401);

        return new SecurityEvent(
                "evt-" + new UUID(random.nextLong(), random.nextLong()).toString().substring(0, 12),
                timestamp,
                config.configId(),
                "pol_web" + (1 + random.nextInt(3)),
                clientIp,
                pick(HOSTS),
                path,
                pick(METHODS),
                statusCode,
                pick(USER_AGENTS),
                rule,
                action,
                geo,
                256 + random.nextInt(8192),
                128 + random.nextInt(4096));
    }

    private Instant randomTimestamp(GeneratorConfig config) {
        long spanSeconds = Math.max(1, config.span().toSeconds());
        return config.startTime().plusSeconds(Math.floorMod(random.nextLong(), spanSeconds));
    }

    private String randomIp() {
        return "203.0." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }

    private <T> T pick(List<T> options) {
        return options.get(random.nextInt(options.size()));
    }
}
