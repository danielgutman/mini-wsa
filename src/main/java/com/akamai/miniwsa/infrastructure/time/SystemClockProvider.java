package com.akamai.miniwsa.infrastructure.time;

import com.akamai.miniwsa.application.ports.ClockProvider;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Production {@link ClockProvider} backed by the system UTC clock. This is the
 * single place the real wall-clock effect is read.
 */
@Component
public class SystemClockProvider implements ClockProvider {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
