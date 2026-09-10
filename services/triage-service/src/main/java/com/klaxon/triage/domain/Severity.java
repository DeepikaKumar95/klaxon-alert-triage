package com.klaxon.triage.domain;

/**
 * Triage severity. Ordered deliberately: ordinal() is used to compare a fallback
 * verdict against an LLM verdict, so the order must stay lowest-to-highest.
 */
public enum Severity {
    INFO, LOW, MEDIUM, HIGH, CRITICAL;

    public static Severity fromOrNull(String raw) {
        if (raw == null) return null;
        try {
            return Severity.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean atLeast(Severity other) {
        return this.ordinal() >= other.ordinal();
    }
}
