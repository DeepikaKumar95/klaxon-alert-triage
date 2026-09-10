package com.klaxon.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.klaxon.triage.api.dto.CreateAlertRequest;
import com.klaxon.triage.api.dto.TriagedAlertView;
import com.klaxon.triage.domain.Severity;
import com.klaxon.triage.llm.GeminiClient;
import com.klaxon.triage.messaging.AlertEvent;
import com.klaxon.triage.repository.EmbeddingRepository;
import com.klaxon.triage.repository.TriageResultRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HTTP only. Ingest publishes to Kafka and returns; triage happens in the pipeline. */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = {"http://localhost:4300", "http://localhost:4200"})
public class AlertController {

    private final KafkaTemplate<String, String> kafka;
    private final TriageResultRepository results;
    private final EmbeddingRepository embeddings;
    private final GeminiClient gemini;
    private final LiveFeed liveFeed;
    private final ObjectMapper json;

    @Value("${klaxon.topics.raw}")
    private String rawTopic;

    public AlertController(KafkaTemplate<String, String> kafka, TriageResultRepository results,
                           EmbeddingRepository embeddings, GeminiClient gemini,
                           LiveFeed liveFeed, ObjectMapper json) {
        this.kafka = kafka;
        this.results = results;
        this.embeddings = embeddings;
        this.gemini = gemini;
        this.liveFeed = liveFeed;
        this.json = json;
    }

    /** 202, not 201: the alert is accepted for triage, and the verdict arrives later. */
    @PostMapping("/alerts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> ingest(@Valid @RequestBody CreateAlertRequest req) throws Exception {
        AlertEvent event = new AlertEvent(
                UUID.randomUUID(), UUID.randomUUID(),
                req.source(), req.service(), req.host(), req.title(), req.body(),
                req.severity(), req.occurredAt() != null ? req.occurredAt() : Instant.now());

        kafka.send(rawTopic, event.alertId().toString(), json.writeValueAsString(event));
        return Map.of("alertId", event.alertId().toString(), "status", "accepted");
    }

    @GetMapping("/alerts")
    public List<TriagedAlertView> recent(@RequestParam(required = false) Severity severity,
                                         @RequestParam(defaultValue = "50") int limit) {
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), 200));
        return severity == null ? results.findRecent(page) : results.findRecentBySeverity(severity, page);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return liveFeed.subscribe();
    }

    /** Powers the dashboard header, and makes the degraded state visible rather than hidden. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Severity s : Severity.values()) bySeverity.put(s.name(), 0L);
        for (Object[] row : results.countBySeverity()) {
            bySeverity.put(((Severity) row[0]).name(), (Long) row[1]);
        }

        return Map.of(
                "bySeverity", bySeverity,
                "total", bySeverity.values().stream().mapToLong(Long::longValue).sum(),
                "embeddings", embeddings.count(),
                "llmEnabled", gemini.isEnabled(),
                "quotaUsedThisMinute", gemini.quotaUsedThisMinute(),
                "subscribers", liveFeed.subscriberCount());
    }
}
