package com.klaxon.triage.api.dto;

import com.klaxon.triage.domain.Severity;
import com.klaxon.triage.domain.TriageResult;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the dashboard feed. A record, not an entity: the API surface is
 * decoupled from the tables, and JPQL builds it directly so there is no N+1.
 */
public record TriagedAlertView(
        UUID alertId,
        String service,
        String host,
        String title,
        String rawSeverity,
        Severity severity,
        String category,
        String summary,
        String hypothesis,
        Float confidence,
        TriageResult.DecidedBy decidedBy,
        String model,
        Integer latencyMs,
        UUID similarAlertId,
        Float similarity,
        Instant occurredAt,
        Instant decidedAt
) {
    /** True when the model disagreed with the severity the sender claimed — the interesting rows. */
    public boolean severityChanged() {
        Severity claimed = Severity.fromOrNull(rawSeverity);
        return claimed != null && claimed != severity;
    }
}
