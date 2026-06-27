package com.akamai.miniwsa.application.ports;

import com.akamai.miniwsa.application.alerting.AlertRule;
import java.util.List;

/**
 * Store for defined {@link AlertRule}s. Separate from event storage — rules are small,
 * config-like state. The default adapter keeps them in memory.
 */
public interface AlertRuleRepository {

    /** Persists a new rule, assigning it an id, and returns the stored rule. */
    AlertRule save(AlertRule rule);

    /** All currently defined rules. */
    List<AlertRule> findAll();
}
