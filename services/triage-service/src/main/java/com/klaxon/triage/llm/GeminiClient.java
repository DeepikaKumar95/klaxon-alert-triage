package com.klaxon.triage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.klaxon.triage.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gemini access for triage and embeddings.
 *
 * Three things here are deliberate and worth reading:
 *  1. The reply is constrained by a response schema, so we parse JSON instead of
 *     hoping the model formatted its prose consistently.
 *  2. Every call goes through a rate limiter sized to the free tier.
 *  3. Identical alerts hit a short-lived cache — a flapping check should cost one
 *     call, not one per firing.
 *
 * Nothing here throws on failure. The caller falls back to deterministic triage,
 * because an alert pipeline that stops when a vendor is down is worse than one
 * that degrades.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final LlmProperties props;
    private final ObjectMapper json;
    private final RestClient http;
    private final RateLimiter limiter;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public GeminiClient(LlmProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.limiter = new RateLimiter(props.getMaxRequestsPerMinute());
        this.http = RestClient.builder().baseUrl(props.getBaseUrl()).build();
    }

    public boolean isEnabled() { return props.isConfigured(); }

    public int quotaUsedThisMinute() { return limiter.usedInLastMinute(); }

    // ---------------------------------------------------------------- triage

    public Optional<TriageVerdict> triage(String alertText, String cacheKey) {
        if (!props.isConfigured()) return Optional.empty();

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isFresh(props.getCacheTtlMinutes())) {
            log.debug("Triage cache hit for {}", cacheKey);
            return Optional.of(cached.verdict());
        }

        if (!limiter.tryAcquire(3000)) {
            log.warn("Gemini per-minute quota exhausted; falling back to rules");
            return Optional.empty();
        }

        long started = System.currentTimeMillis();
        try {
            JsonNode response = http.post()
                    .uri("/models/{model}:generateContent", props.getChatModel())
                    .header("X-goog-api-key", props.getApiKey())   // header, not a query param: keys leak via URLs
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(alertText))
                    .retrieve()
                    .body(JsonNode.class);

            int latency = (int) (System.currentTimeMillis() - started);
            Optional<TriageVerdict> verdict = parse(response, latency);
            verdict.ifPresent(v -> cache.put(cacheKey, new CacheEntry(v, Instant.now())));
            return verdict;

        } catch (Exception e) {
            log.warn("Gemini triage failed ({}); falling back to rules", e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> requestBody(String alertText) {
        return Map.of(
                "systemInstruction", Map.of("parts", java.util.List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", java.util.List.of(Map.of("parts", java.util.List.of(Map.of("text", alertText)))),
                "generationConfig", Map.of(
                        "temperature", 0.1,
                        "responseMimeType", "application/json",
                        "responseSchema", RESPONSE_SCHEMA));
    }

    private Optional<TriageVerdict> parse(JsonNode response, int latencyMs) {
        if (response == null) return Optional.empty();
        JsonNode text = response.at("/candidates/0/content/parts/0/text");
        if (text.isMissingNode() || text.asText().isBlank()) {
            log.warn("Gemini returned no candidate text");
            return Optional.empty();
        }
        try {
            JsonNode v = json.readTree(text.asText());
            Severity severity = Severity.fromOrNull(v.path("severity").asText(null));
            if (severity == null) {
                log.warn("Gemini returned an unusable severity: {}", v.path("severity").asText());
                return Optional.empty();
            }
            return Optional.of(new TriageVerdict(
                    severity,
                    v.path("category").asText("unknown"),
                    v.path("summary").asText(""),
                    v.path("hypothesis").asText(""),
                    (float) v.path("confidence").asDouble(0.0),
                    response.at("/usageMetadata/promptTokenCount").asInt(0),
                    response.at("/usageMetadata/candidatesTokenCount").asInt(0),
                    latencyMs));
        } catch (Exception e) {
            log.warn("Gemini returned unparseable JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------ embeddings

    public Optional<float[]> embed(String text) {
        if (!props.isConfigured()) return Optional.empty();
        if (!limiter.tryAcquire(3000)) return Optional.empty();

        try {
            JsonNode response = http.post()
                    .uri("/models/{model}:embedContent", props.getEmbeddingModel())
                    .header("X-goog-api-key", props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", "models/" + props.getEmbeddingModel(),
                            "content", Map.of("parts", java.util.List.of(Map.of("text", text))),
                            // Pin the width to the vector(768) column rather than migrating the schema
                            // every time the default output size changes.
                            "outputDimensionality", props.getEmbeddingDimensions()))
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode values = response == null ? null : response.at("/embedding/values");
            if (values == null || !values.isArray() || values.isEmpty()) {
                log.warn("Gemini embedding response had no values");
                return Optional.empty();
            }
            float[] out = new float[values.size()];
            for (int i = 0; i < values.size(); i++) out[i] = (float) values.get(i).asDouble();
            if (out.length != props.getEmbeddingDimensions()) {
                log.warn("Embedding dimension {} does not match the configured {}; skipping vector store",
                        out.length, props.getEmbeddingDimensions());
                return Optional.empty();
            }
            return Optional.of(out);

        } catch (Exception e) {
            log.warn("Gemini embedding failed ({})", e.getMessage());
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- detail

    private record CacheEntry(TriageVerdict verdict, Instant at) {
        boolean isFresh(int ttlMinutes) {
            return Duration.between(at, Instant.now()).toMinutes() < ttlMinutes;
        }
    }

    private static final String SYSTEM_PROMPT = """
            You are an on-call triage assistant. You are given one monitoring alert.

            Assign a severity using operational impact, not the wording of the alert:
              CRITICAL  customer-facing outage or data loss happening now
              HIGH      severe degradation, or an outage is imminent
              MEDIUM    a real problem with no immediate customer impact
              LOW       minor or self-healing
              INFO      informational, or expected during normal operation

            Disagree with the reported severity when the detail does not support it.
            A single retried timeout is not CRITICAL. Sustained 5xx on a checkout
            path is. Health-check flapping on one host of many is usually LOW.

            hypothesis: the most likely proximate cause, one sentence, or "" when
            the alert genuinely does not say enough. Never invent specifics that
            are not present in the alert.

            confidence: 0.0-1.0, your own certainty in the severity you assigned.
            """;

    private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "severity", Map.of("type", "STRING",
                            "enum", java.util.List.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")),
                    "category", Map.of("type", "STRING",
                            "enum", java.util.List.of(TriageVerdict.CATEGORIES)),
                    "summary", Map.of("type", "STRING"),
                    "hypothesis", Map.of("type", "STRING"),
                    "confidence", Map.of("type", "NUMBER")),
            "required", java.util.List.of("severity", "category", "summary", "hypothesis", "confidence"));
}
