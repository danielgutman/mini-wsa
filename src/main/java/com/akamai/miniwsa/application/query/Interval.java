package com.akamai.miniwsa.application.query;

import java.time.Duration;

/**
 * Time-series bucket size. The wire codes ({@code 1m}, {@code 5m}, {@code 1h}) are the
 * accepted {@code interval} query-param values.
 */
public enum Interval {

    ONE_MINUTE("1m", Duration.ofMinutes(1)),
    FIVE_MINUTES("5m", Duration.ofMinutes(5)),
    ONE_HOUR("1h", Duration.ofHours(1));

    /** Validation pattern for the {@code interval} request param. */
    public static final String PATTERN = "1m|5m|1h";

    private final String code;
    private final Duration duration;

    Interval(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }

    public String code() {
        return code;
    }

    public Duration duration() {
        return duration;
    }

    public static Interval fromCode(String code) {
        for (Interval interval : values()) {
            if (interval.code.equals(code)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("Unknown interval: " + code);
    }
}
