package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.IngestEventRequest;
import com.akamai.miniwsa.api.dto.IngestResponse;
import com.akamai.miniwsa.api.validation.BatchSizeWithinLimit;
import com.akamai.miniwsa.application.EventIngestionService;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 */
@RestController
@RequestMapping("/v1/events")
@Tag(name = "Ingestion", description = "Accept and enrich security events")
public class EventIngestionController {

    private final EventIngestionService ingestionService;

    public EventIngestionController(EventIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ingest a single event or a batch; returns the number accepted")
    public IngestResponse ingest(
            @RequestBody @NotEmpty @BatchSizeWithinLimit @Valid List<@Valid IngestEventRequest> events) {
        List<SecurityEvent> domainEvents = events.stream()
                .map(IngestEventRequest::toDomain)
                .toList();
        return new IngestResponse(ingestionService.ingest(domainEvents));
    }
}
