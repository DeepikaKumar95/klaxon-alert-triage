package com.klaxon.triage.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.klaxon.triage.api.LiveFeed;
import com.klaxon.triage.service.TriageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Dispatch only. Every decision lives in TriageService; this class exists to turn
 * a Kafka record into a call and to fan the result out afterwards.
 */
@Component
public class AlertListener {

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);

    private final TriageService triage;
    private final LiveFeed liveFeed;
    private final ObjectMapper json;

    public AlertListener(TriageService triage, LiveFeed liveFeed, ObjectMapper json) {
        this.triage = triage;
        this.liveFeed = liveFeed;
        this.json = json;
    }

    @KafkaListener(topics = "${klaxon.topics.raw}", groupId = "triage")
    public void on(String payload) throws Exception {
        AlertEvent event = json.readValue(payload, AlertEvent.class);
        if (event.eventId() == null || event.alertId() == null) {
            // Unrecoverable: retrying cannot supply a missing id, so let it reach the DLT.
            throw new IllegalArgumentException("Alert event is missing eventId or alertId");
        }

        triage.handle(event).ifPresent(view -> {
            triage.publish(view);   // after commit
            liveFeed.broadcast(view);
            log.info("Triaged {} as {} ({}) via {}",
                    view.alertId(), view.severity(), view.category(), view.decidedBy());
        });
    }
}
