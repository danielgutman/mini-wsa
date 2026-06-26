package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.IngestEventRequest;
import com.akamai.miniwsa.api.dto.IngestResponse;
import com.akamai.miniwsa.application.EventIngestionService;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion endpoint. Accepts a single event object or a JSON array (Jackson's
 * {@code ACCEPT_SINGLE_VALUE_AS_ARRAY} binds both to the list), validates each via
 * {@code @Valid}, converts to the domain model, and delegates to the service.
 *
 * <p>Thin by design: it never builds error responses or throws — invalid input
 * makes Spring raise {@code MethodArgumentNotValidException} /
 * {@code HttpMessageNotReadableException}, both handled centrally in
 * {@code ErrorHandlingAdvice}. The batch is capped at {@value #MAX_BATCH_SIZE} events so an
 * oversized array is rejected with a 400 rather than driving a memory/time blowup.
 */
@RestController
@RequestMapping("/v1/events")
public class EventIngestionController {

    /** Maximum events accepted per request; large loads should be chunked by the client. */
    static final int MAX_BATCH_SIZE = 10_000;

    private final EventIngestionService ingestionService;

    public EventIngestionController(EventIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestResponse ingest(
            @RequestBody
            @NotEmpty
            @Size(max = MAX_BATCH_SIZE, message = "batch must not exceed " + MAX_BATCH_SIZE + " events")
            @Valid List<@Valid IngestEventRequest> events) {
        List<SecurityEvent> domainEvents = events.stream()
                .map(IngestEventRequest::toDomain)
                .toList();
        return new IngestResponse(ingestionService.ingest(domainEvents));
    }
}
