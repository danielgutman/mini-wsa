package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.model.GeoLocation;

/**
 * Request-side representation of the optional {@code geoLocation} object.
 * Both fields are optional; a missing object maps to {@code null} downstream.
 */
public record GeoLocationDto(
        String country,
        String city
) {

    public GeoLocation toDomain() {
        return new GeoLocation(country, city);
    }
}
