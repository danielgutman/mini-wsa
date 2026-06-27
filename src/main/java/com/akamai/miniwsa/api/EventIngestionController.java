package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.IngestEventRequest;
import com.akamai.miniwsa.api.dto.IngestResponse;
import com.akamai.miniwsa.api.validation.BatchSizeWithinLimit;
import com.akamai.miniwsa.application.EventIngestionService;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion endpoint: accepts a single event object or a JSON array (both bind to the list),
 * validates the request, maps it to the domain model, and delegates to the service.
 *
 * <p>Thin by design — it never builds error responses or throws. Invalid input (unknown enums,
 * malformed timestamps, missing fields, an oversized batch) is rejected by Bean Validation and
 * rendered centrally in {@code ErrorHandlingAdvice}.
 */
@RestController
@RequestMapping("/v1/events")
public class EventIngestionController {

    private final EventIngestionService ingestionService;

    public EventIngestionController(EventIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestResponse ingest(
            @RequestBody @NotEmpty @BatchSizeWithinLimit @Valid List<@Valid IngestEventRequest> events) {
        List<SecurityEvent> domainEvents = events.stream()
                .map(IngestEventRequest::toDomain)
                .toList();
        return new IngestResponse(ingestionService.ingest(domainEvents));
    }
}
