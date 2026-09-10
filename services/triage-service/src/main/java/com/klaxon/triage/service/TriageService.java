package com.klaxon.triage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.klaxon.triage.api.dto.TriagedAlertView;
import com.klaxon.triage.domain.Alert;
import com.klaxon.triage.domain.ProcessedEvent;
import com.klaxon.triage.domain.TriageResult;
import com.klaxon.triage.llm.GeminiClient;
import com.klaxon.triage.llm.TriageVerdict;
import com.klaxon.triage.messaging.AlertEvent;
import com.klaxon.triage.repository.AlertRepository;
import com.klaxon.triage.repository.EmbeddingRepository;
import com.klaxon.triage.repository.ProcessedEventRepository;
import com.klaxon.triage.repository.TriageResultRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The triage pipeline stage. Business rules live here, never in the listener.
 *
 * Order matters: persist the alert first so the signal survives even if every
 * downstream step fails, then enrich.
 */
@Service
public class TriageService {

    private static final Logger log = LoggerFactory.getLogger(TriageService.class);
    private static final String CONSUMER_GROUP = "triage";

    /** Cosine distance below which two alerts are "the same kind of thing". */
    private static final double SIMILARITY_CUTOFF = 0.25;

    private final AlertRepository alerts;
    private final TriageResultRepository results;
    private final ProcessedEventRepository processed;
    private final EmbeddingRepository embeddings;
    private final GeminiClient gemini;
    private final RuleBasedTriage rules;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    private final Counter triagedByLlm;
    private final Counter triagedByFallback;
    private final Counter duplicatesSkipped;
    private final Counter promptTokens;

    @Value("${klaxon.topics.triaged}")
    private String triagedTopic;

    public TriageService(AlertRepository alerts, TriageResultRepository results,
                         ProcessedEventRepository processed, EmbeddingRepository embeddings,
                         GeminiClient gemini, RuleBasedTriage rules,
                         KafkaTemplate<String, String> kafka, ObjectMapper json,
                         MeterRegistry meters) {
        this.alerts = alerts;
        this.results = results;
        this.processed = processed;
        this.embeddings = embeddings;
        this.gemini = gemini;
        this.rules = rules;
        this.kafka = kafka;
        this.json = json;
        this.triagedByLlm = meters.counter("klaxon_triaged_total", "decided_by", "llm");
        this.triagedByFallback = meters.counter("klaxon_triaged_total", "decided_by", "fallback");
        this.duplicatesSkipped = meters.counter("klaxon_duplicate_events_total");
        this.promptTokens = meters.counter("klaxon_prompt_tokens_total");
    }

    /**
     * Idempotent: the processed-events marker is written in the same transaction as
     * the triage, so a redelivery is a no-op rather than a second verdict.
     */
    @Transactional
    public Optional<TriagedAlertView> handle(AlertEvent event) {
        if (processed.existsByEventIdAndConsumerGroup(event.eventId(), CONSUMER_GROUP)) {
            duplicatesSkipped.increment();
            log.debug("Event {} already processed; skipping", event.eventId());
            return Optional.empty();
        }

        // saveAndFlush, not save: the embedding is written with JdbcTemplate further down,
        // and raw JDBC cannot see an INSERT that Hibernate is still holding in the
        // persistence context — the foreign key on alert_embeddings would reject it.
        Alert alert = alerts.saveAndFlush(Alert.of(
                event.alertId(), event.source(), event.service(), event.host(),
                event.title(), event.body(), event.severity(), event.occurredAt()));

        TriageResult result = decide(alert);
        attachSimilarIncident(alert, result);

        results.save(result);
        processed.save(new ProcessedEvent(event.eventId(), CONSUMER_GROUP));

        // Returned, not published: the caller fans out after this transaction commits.
        // Publishing here would be a dual write — the same hazard an outbox exists to prevent.
        return results.findViewByAlertId(alert.getId()).stream().findFirst();
    }

    /** Model first, rules when the model cannot answer. Never both. */
    private TriageResult decide(Alert alert) {
        Optional<TriageVerdict> llm = gemini.triage(alert.toPromptText(), alert.getFingerprint());

        if (llm.isPresent()) {
            TriageVerdict v = llm.get();
            triagedByLlm.increment();
            promptTokens.increment(v.promptTokens());
            return TriageResult.of(alert.getId(), v.severity(), v.category(), v.summary(),
                    v.hypothesis(), v.confidence(), TriageResult.DecidedBy.LLM,
                    null, v.promptTokens(), v.outputTokens(), v.latencyMs());
        }

        TriageVerdict v = rules.triage(alert);
        triagedByFallback.increment();
        return TriageResult.of(alert.getId(), v.severity(), v.category(), v.summary(),
                v.hypothesis(), v.confidence(), TriageResult.DecidedBy.FALLBACK,
                null, 0, 0, 0);
    }

    /**
     * Embed the alert and look for the nearest previously-seen one. Best effort:
     * without a key or with the quota spent there is simply no recall, and the
     * verdict stands on its own.
     */
    private void attachSimilarIncident(Alert alert, TriageResult result) {
        Optional<float[]> vector = gemini.embed(alert.toPromptText());
        if (vector.isEmpty()) return;

        embeddings.findNearest(alert.getId(), vector.get(), SIMILARITY_CUTOFF)
                .ifPresent(match -> result.linkSimilar(match.alertId(), match.similarity()));

        embeddings.save(alert.getId(), vector.get());
    }

    /**
     * Called by the listener once the triage transaction has committed, so a
     * published verdict is always one that is already durable.
     */
    public void publish(TriagedAlertView view) {
        try {
            kafka.send(triagedTopic, view.alertId().toString(), json.writeValueAsString(view));
        } catch (Exception e) {
            log.warn("Could not publish triaged alert {}: {}", view.alertId(), e.getMessage());
        }
    }
}
