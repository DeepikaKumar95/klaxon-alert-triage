package com.klaxon.triage.llm;

import com.klaxon.triage.domain.Severity;

/**
 * What the model is asked to return. This shape is enforced by Gemini's response
 * schema, so the reply is parsed as JSON rather than scraped out of prose.
 */
public record TriageVerdict(
        Severity severity,
        String category,
        String summary,
        String hypothesis,
        float confidence,
        int promptTokens,
        int outputTokens,
        int latencyMs
) {
    public static final String[] CATEGORIES = {
            "availability", "latency", "saturation", "data-integrity",
            "security", "deployment", "dependency", "noise"
    };
}
