package com.akamai.miniwsa.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal liveness endpoint used to confirm the application is up. Richer health/metrics
 * are available under {@code /actuator/health}.
 */
@RestController
public class PingController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok", "service", "mini-wsa");
    }
}
