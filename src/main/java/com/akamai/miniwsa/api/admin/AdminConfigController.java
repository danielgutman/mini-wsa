package com.akamai.miniwsa.api.admin;

import com.akamai.miniwsa.config.tunables.TunablesHolder;
import com.akamai.miniwsa.config.tunables.TunablesProperties;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime configuration of the tunable enrichment rules — read and replace without a restart.
 *
 * <ul>
 *   <li>{@code GET /v1/admin/config} — the current settings.</li>
 *   <li>{@code PUT /v1/admin/config} — validate and atomically swap them (takes effect on the
 *       next ingested batch). Invalid bodies are rejected with a 400 by the central handler.</li>
 * </ul>
 *
 * <p>Durations use ISO-8601 (e.g. {@code "PT10M"}). This endpoint is unauthenticated here (auth
 * is a documented non-goal); in production it would sit behind admin authn/authz.
 */
@RestController
@RequestMapping("/v1/admin/config")
public class AdminConfigController {

    private final TunablesHolder holder;

    public AdminConfigController(TunablesHolder holder) {
        this.holder = holder;
    }

    @GetMapping
    public TunablesProperties current() {
        return TunablesProperties.fromTunables(holder.current());
    }

    @PutMapping
    public TunablesProperties replace(@RequestBody @Valid TunablesProperties request) {
        holder.replaceWith(request.toTunables());
        return TunablesProperties.fromTunables(holder.current());
    }
}
