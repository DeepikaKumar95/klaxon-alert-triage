package com.klaxon.triage.domain;

import jakarta.persistence.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A raw alert as it arrived. Immutable once stored: a re-triage writes a new
 * TriageResult rather than mutating the signal we were given.
 */
@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    private UUID id;

    /** service + title + host, hashed. The same condition firing twice is one incident. */
    private String fingerprint;

    private String source;
    private String service;
    private String host;
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    /** What the sender claimed. Kept so the demo can show the LLM disagreeing with it. */
    @Column(name = "raw_severity")
    private String rawSeverity;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    protected Alert() {}

    public static Alert of(UUID id, String source, String service, String host,
                           String title, String body, String rawSeverity, Instant occurredAt) {
        Alert a = new Alert();
        a.id = id;
        a.source = source;
        a.service = service;
        a.host = host;
        a.title = title;
        a.body = body;
        a.rawSeverity = rawSeverity;
        a.occurredAt = occurredAt != null ? occurredAt : Instant.now();
        a.receivedAt = Instant.now();
        a.fingerprint = fingerprintOf(service, title, host);
        return a;
    }

    public static String fingerprintOf(String service, String title, String host) {
        String basis = (n(service) + "|" + n(title) + "|" + n(host)).toLowerCase();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(basis.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String n(String s) { return s == null ? "" : s.trim(); }

    /** What the model actually reads. Kept here so prompt and storage cannot drift apart. */
    public String toPromptText() {
        return """
               service: %s
               host: %s
               source: %s
               reported severity: %s
               title: %s
               detail: %s""".formatted(n(service), n(host), n(source),
                                       rawSeverity == null ? "none" : rawSeverity, n(title), n(body));
    }

    public UUID getId() { return id; }
    public String getFingerprint() { return fingerprint; }
    public String getSource() { return source; }
    public String getService() { return service; }
    public String getHost() { return host; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getRawSeverity() { return rawSeverity; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
