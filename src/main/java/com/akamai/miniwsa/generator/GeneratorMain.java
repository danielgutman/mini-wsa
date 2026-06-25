package com.akamai.miniwsa.generator;

import com.akamai.miniwsa.domain.model.SecurityEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entry point for the data generator. Produces a JSON array of events that can be fed
 * straight to {@code POST /v1/events/ingest}.
 *
 * <pre>
 *   ./mvnw -q compile exec:java -Dexec.args="--count 10000 --output generated-events.json"
 *   curl -X POST http://localhost:8080/v1/events/ingest \
 *        -H "Content-Type: application/json" --data @generated-events.json
 * </pre>
 *
 * Options (all optional): {@code --count}, {@code --output}, {@code --seed},
 * {@code --config-id}, {@code --waves}, {@code --wave-size}.
 */
public final class GeneratorMain {

    private GeneratorMain() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = parseOptions(args);
        GeneratorConfig defaults = GeneratorConfig.defaults();

        int count = intOption(options, "count", defaults.totalEvents());
        long configId = Long.parseLong(options.getOrDefault("config-id", String.valueOf(defaults.configId())));
        int waves = intOption(options, "waves", defaults.waveCount());
        int waveSize = intOption(options, "wave-size", defaults.waveSize());
        long seed = Long.parseLong(options.getOrDefault("seed", "42"));
        String output = options.getOrDefault("output", "generated-events.json");

        GeneratorConfig config = new GeneratorConfig(
                count, configId, waves, waveSize, defaults.startTime(), Duration.ofHours(24));
        List<SecurityEvent> events = new SecurityEventGenerator(seed).generate(config);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(output), events);

        // Print the seed so any dataset (and any bug it surfaces) is reproducible.
        System.out.printf("Generated %d events (seed=%d, %d in %d attack waves) -> %s%n",
                events.size(), seed, waves * waveSize, waves, output);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        return options;
    }

    private static int intOption(Map<String, String> options, String key, int defaultValue) {
        return options.containsKey(key) ? Integer.parseInt(options.get(key)) : defaultValue;
    }
}
