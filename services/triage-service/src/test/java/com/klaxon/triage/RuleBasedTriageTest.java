package com.klaxon.triage;

import com.klaxon.triage.domain.Alert;
import com.klaxon.triage.domain.Severity;
import com.klaxon.triage.llm.TriageVerdict;
import com.klaxon.triage.service.RuleBasedTriage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fallback is the floor the model has to beat, so it needs to be worth
 * something on its own.
 */
class RuleBasedTriageTest {

    private final RuleBasedTriage rules = new RuleBasedTriage();

    private static Alert alert(String title, String body, String reported) {
        return Alert.of(UUID.randomUUID(), "test", "svc", "host-1", title, body, reported, Instant.now());
    }

    @Test
    @DisplayName("an outage is CRITICAL regardless of what the sender called it")
    void outageIsCritical() {
        TriageVerdict v = rules.triage(alert("Total outage", "Complete outage of the checkout path.", "info"));

        assertThat(v.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(v.category()).isEqualTo("availability");
    }

    @Test
    @DisplayName("keyword order decides: a 5xx storm outranks a latency mention")
    void moreSevereRuleWins() {
        TriageVerdict v = rules.triage(alert("Errors", "Sustained 5xx and rising latency on the API.", null));

        assertThat(v.severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    @DisplayName("with no matching rule it keeps the severity the sender reported")
    void fallsBackToReportedSeverity() {
        TriageVerdict v = rules.triage(alert("Something odd", "A thing happened that no rule covers.", "LOW"));

        assertThat(v.severity()).isEqualTo(Severity.LOW);
        assertThat(v.category()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("with neither a rule nor a reported severity it defaults to MEDIUM")
    void defaultsToMedium() {
        TriageVerdict v = rules.triage(alert("Something odd", "No rule covers this.", null));

        assertThat(v.severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    @DisplayName("it never invents a root cause — that needs a model")
    void neverGuessesAHypothesis() {
        TriageVerdict v = rules.triage(alert("Disk full", "Disk full on the primary.", "warning"));

        assertThat(v.hypothesis()).isEmpty();
        assertThat(v.confidence()).isLessThan(0.5f);
    }
}
