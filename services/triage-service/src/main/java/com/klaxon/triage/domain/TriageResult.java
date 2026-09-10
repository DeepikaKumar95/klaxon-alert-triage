package com.klaxon.triage.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The verdict for one alert. decided_by is the honest part: it records whether a
 * model or the deterministic fallback produced this, so the dashboard never
 * implies an LLM was involved when it was not.
 */
@Entity
@Table(name = "triage_results")
public class TriageResult {

    public enum DecidedBy { LLM, FALLBACK }

    @Id
    @Column(name = "alert_id")
    private UUID alertId;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private String category;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "text")
    private String hypothesis;

    private Float confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by")
    private DecidedBy decidedBy;

    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** Nearest prior alert by embedding distance — the "we've seen this before" signal. */
    @Column(name = "similar_alert_id")
    private UUID similarAlertId;

    private Float similarity;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected TriageResult() {}

    public static TriageResult of(UUID alertId, Severity severity, String category, String summary,
                                  String hypothesis, Float confidence, DecidedBy decidedBy, String model,
                                  Integer promptTokens, Integer outputTokens, Integer latencyMs) {
        TriageResult t = new TriageResult();
        t.alertId = alertId;
        t.severity = severity;
        t.category = category;
        t.summary = summary;
        t.hypothesis = hypothesis;
        t.confidence = confidence;
        t.decidedBy = decidedBy;
        t.model = model;
        t.promptTokens = promptTokens;
        t.outputTokens = outputTokens;
        t.latencyMs = latencyMs;
        t.decidedAt = Instant.now();
        return t;
    }

    public void linkSimilar(UUID similarAlertId, float similarity) {
        this.similarAlertId = similarAlertId;
        this.similarity = similarity;
    }

    public UUID getAlertId() { return alertId; }
    public Severity getSeverity() { return severity; }
    public String getCategory() { return category; }
    public String getSummary() { return summary; }
    public String getHypothesis() { return hypothesis; }
    public Float getConfidence() { return confidence; }
    public DecidedBy getDecidedBy() { return decidedBy; }
    public String getModel() { return model; }
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public Integer getLatencyMs() { return latencyMs; }
    public UUID getSimilarAlertId() { return similarAlertId; }
    public Float getSimilarity() { return similarity; }
    public Instant getDecidedAt() { return decidedAt; }
}
