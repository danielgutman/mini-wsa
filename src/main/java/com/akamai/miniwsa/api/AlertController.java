package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.AlertEvaluationResponse;
import com.akamai.miniwsa.api.dto.AlertRuleResponse;
import com.akamai.miniwsa.api.dto.DefineAlertRequest;
import com.akamai.miniwsa.application.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alerting endpoints (bonus): define threshold rules, then evaluate which are firing right now.
 * Thin — validates and delegates to {@link AlertService}.
 */
@RestController
@RequestMapping("/v1/alerts")
@Tag(name = "Alerting", description = "Define threshold rules and evaluate firing alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @PostMapping("/define")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Define a rule: more than N events of a category within Y minutes")
    public AlertRuleResponse define(@Valid @RequestBody DefineAlertRequest request) {
        return AlertRuleResponse.from(alertService.define(request.toDomain()));
    }

    @GetMapping("/evaluate")
    @Operation(summary = "Evaluate all defined rules against current data; return firing alerts")
    public AlertEvaluationResponse evaluate() {
        return AlertEvaluationResponse.from(alertService.evaluate());
    }
}
