package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.application.query.SamplePage;
import java.util.List;

/**
 * Response body for {@code GET /v1/events/samples}: the page of matching events plus the
 * {@code total} match count and the effective {@code limit}/{@code offset}.
 */
public record SamplesResponse(
        long total,
        int limit,
        int offset,
        List<SampleItem> items
) {

    public static SamplesResponse from(SamplePage page) {
        return new SamplesResponse(
                page.total(),
                page.limit(),
                page.offset(),
                page.items().stream().map(SampleItem::from).toList());
    }
}
