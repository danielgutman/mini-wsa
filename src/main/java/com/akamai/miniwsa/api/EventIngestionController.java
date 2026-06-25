package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.ApiError;
import com.akamai.miniwsa.api.dto.IngestEventRequest;
import com.akamai.miniwsa.api.dto.IngestResponse;
import com.akamai.miniwsa.api.exception.InvalidRequestException;
import com.akamai.miniwsa.application.EventIngestionService;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion endpoint. Accepts either a single event object or a JSON array of
 * events, validates each, converts to the domain model, and delegates to the
 * application service. Thin by design — all error rendering is centralized in
 * {@code ErrorHandlingAdvice}.
 */
@RestController
@RequestMapping("/v1/events")
public class EventIngestionController {

    private final EventIngestionService ingestionService;
    private final Validator validator;
    private final ObjectMapper objectMapper;

    public EventIngestionController(EventIngestionService ingestionService,
                                    Validator validator,
                                    ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestResponse ingest(@RequestBody JsonNode body) {
        List<IngestEventRequest> requests = parse(body);
        validate(requests);

        List<SecurityEvent> events = requests.stream()
                .map(IngestEventRequest::toDomain)
                .toList();

        int ingested = ingestionService.ingest(events);
        return new IngestResponse(ingested);
    }

    /** Normalizes single-or-array input into a list of typed requests. */
    private List<IngestEventRequest> parse(JsonNode body) {
        if (body == null || body.isNull()) {
            throw new InvalidRequestException("Request body is required");
        }

        List<JsonNode> nodes = new ArrayList<>();
        if (body.isArray()) {
            body.forEach(nodes::add);
        } else if (body.isObject()) {
            nodes.add(body);
        } else {
            throw new InvalidRequestException("Expected an event object or an array of events");
        }
        if (nodes.isEmpty()) {
            throw new InvalidRequestException("At least one event is required");
        }

        List<IngestEventRequest> requests = new ArrayList<>(nodes.size());
        for (JsonNode node : nodes) {
            try {
                requests.add(objectMapper.treeToValue(node, IngestEventRequest.class));
            } catch (JsonProcessingException ex) {
                // Unknown enum, bad timestamp format, wrong field type, etc.
                throw new InvalidRequestException("Malformed event: " + ex.getOriginalMessage());
            }
        }
        return requests;
    }

    /** Bean-validates each request, aggregating field errors across the batch. */
    private void validate(List<IngestEventRequest> requests) {
        boolean batch = requests.size() > 1;
        List<ApiError.FieldError> details = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            String prefix = batch ? "[" + i + "]." : "";
            for (ConstraintViolation<IngestEventRequest> violation : validator.validate(requests.get(i))) {
                details.add(new ApiError.FieldError(prefix + violation.getPropertyPath(), violation.getMessage()));
            }
        }

        if (!details.isEmpty()) {
            throw new InvalidRequestException("Request validation failed", details);
        }
    }
}
