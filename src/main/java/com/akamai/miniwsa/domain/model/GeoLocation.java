package com.akamai.miniwsa.domain.model;

/**
 * Approximate geographic origin of the client, as reported by the source DLR.
 */
public record GeoLocation(
        String country,
        String city
) {
}
