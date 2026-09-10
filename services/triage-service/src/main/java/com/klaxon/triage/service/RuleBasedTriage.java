package com.klaxon.triage.service;

import com.klaxon.triage.domain.Alert;
import com.klaxon.triage.domain.Severity;
import com.klaxon.triage.llm.TriageVerdict;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic triage, used whenever the model is unavailable, rate limited, or
 * returns something unusable.
 *
 * This exists so the pipeline degrades instead of stopping, and so there is always
 * a baseline to measure the model against — a model that cannot beat keyword
 * matching is not earning its latency.
 */
@Component
public class RuleBasedTriage {

    private record Rule(Severity severity, String category, List<String> keywords) {}

    // First match wins, so the list runs most severe to least.
    private static final List<Rule> RULES = List.of(
            new Rule(Severity.CRITICAL, "availability",
                    List.of("outage", "data loss", "corrupt", "unavailable", "all requests failing", "cannot connect")),
            new Rule(Severity.HIGH, "availability",
                    List.of("5xx", "500 ", "503", "crash", "oom", "out of memory", "panic", "deadlock", "down")),
            new Rule(Severity.HIGH, "saturation",
                    List.of("disk full", "no space", "quota exceeded", "connection pool exhausted")),
            new Rule(Severity.MEDIUM, "latency",
                    List.of("latency", "slow", "p99", "timeout", "timed out")),
            new Rule(Severity.MEDIUM, "saturation",
                    List.of("cpu", "memory", "heap", "lag", "backlog", "queue depth")),
            new Rule(Severity.MEDIUM, "security",
                    List.of("unauthorized", "forbidden", "certificate", "expired", "auth failure")),
            new Rule(Severity.LOW, "deployment",
                    List.of("deploy", "rollout", "restart", "scaled")),
            new Rule(Severity.LOW, "noise",
                    List.of("flap", "heartbeat", "health check", "probe")));

    public TriageVerdict triage(Alert alert) {
        String haystack = (alert.getTitle() + " " + alert.getBody()).toLowerCase(Locale.ROOT);

        for (Rule rule : RULES) {
            for (String keyword : rule.keywords()) {
                if (haystack.contains(keyword)) {
                    return verdict(rule.severity(), rule.category(),
                            "Matched the phrase \"" + keyword + "\".");
                }
            }
        }

        // Nothing matched: trust the sender's own severity if it gave one, else MEDIUM.
        Severity claimed = Severity.fromOrNull(alert.getRawSeverity());
        return verdict(claimed != null ? claimed : Severity.MEDIUM, "unknown",
                claimed != null ? "No rule matched; kept the reported severity."
                                : "No rule matched and no severity was reported.");
    }

    private TriageVerdict verdict(Severity severity, String category, String why) {
        return new TriageVerdict(severity, category, why,
                "", // never guess a cause without a model
                0.3f, 0, 0, 0);
    }
}
