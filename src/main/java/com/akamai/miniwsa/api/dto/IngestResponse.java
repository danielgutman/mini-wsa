package com.akamai.miniwsa.api.dto;

/**
 * Success response for {@code POST /v1/events/ingest}: the number of events
 * accepted and enriched. Kept deliberately small — the enriched events are not
 * echoed back.
 */
public record IngestResponse(int ingested) {
}
