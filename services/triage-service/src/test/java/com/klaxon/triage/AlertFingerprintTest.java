package com.klaxon.triage;

import com.klaxon.triage.domain.Alert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint is what makes a flapping check cost one model call instead of
 * one per firing, so its stability is a cost control, not a detail.
 */
class AlertFingerprintTest {

    @Test
    @DisplayName("the same condition on the same host fingerprints identically")
    void stableForTheSameCondition() {
        String a = Alert.fingerprintOf("checkout-api", "Sustained 5xx", "host-1");
        String b = Alert.fingerprintOf("checkout-api", "Sustained 5xx", "host-1");

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("case and surrounding whitespace do not create a new incident")
    void insensitiveToCaseAndPadding() {
        String a = Alert.fingerprintOf("checkout-api", "Sustained 5xx", "host-1");
        String b = Alert.fingerprintOf("  CHECKOUT-API ", "  sustained 5XX  ", " host-1 ");

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("the same condition on a different host is a different incident")
    void differsByHost() {
        String a = Alert.fingerprintOf("checkout-api", "Sustained 5xx", "host-1");
        String b = Alert.fingerprintOf("checkout-api", "Sustained 5xx", "host-2");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("a missing host is handled rather than throwing")
    void toleratesNulls() {
        assertThat(Alert.fingerprintOf("svc", "title", null)).hasSize(32);
    }
}
