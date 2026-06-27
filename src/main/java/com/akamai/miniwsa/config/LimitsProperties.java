package com.akamai.miniwsa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Operational limits, externalized so they can be tuned per environment without a code change.
 * Bound from {@code miniwsa.limits} (overridable by env vars / a k8s ConfigMap); applying a new
 * value is a config update + rolling restart — no custom endpoint needed. The scoring rules are
 * deliberately <em>not</em> here: they are part of the analytics contract and stay fixed in code.
 *
 * @param maxBatchSize    max events accepted in one ingest request (larger → 400)
 * @param summaryTopLimit how many entries the summary returns for top attackers / top targeted paths
 */
@ConfigurationProperties(prefix = "miniwsa.limits")
public record LimitsProperties(
        @DefaultValue("10000") int maxBatchSize,
        @DefaultValue("10") int summaryTopLimit
) {
}
