package com.klaxon.triage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.klaxon.triage.messaging.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Emits realistic alerts so the dashboard has something to show without wiring up
 * a real monitoring system.
 *
 * The samples are chosen so the model has something to disagree with: several
 * report a severity that the detail does not support, which is the whole point of
 * the demo.
 */
@Component
@ConditionalOnProperty(prefix = "klaxon.generator", name = "enabled", havingValue = "true")
public class SyntheticGenerator {

    private static final Logger log = LoggerFactory.getLogger(SyntheticGenerator.class);

    private record Sample(String service, String host, String title, String body, String reported) {}

    private static final List<Sample> SAMPLES = List.of(
            new Sample("checkout-api", "checkout-7f9c", "Sustained 5xx on POST /checkout",
                    "Error rate 34% over 6 minutes. Upstream payment-gateway returning 503. "
                    + "Cart abandonment climbing. No recent deploy.", "warning"),
            new Sample("payments-worker", "pay-2a11", "Kafka consumer lag growing",
                    "Consumer group payments lag 48,000 and rising for 12 minutes across 6 partitions. "
                    + "Processing rate halved after the 14:02 rollout.", "warning"),
            new Sample("edge-proxy", "edge-01", "Health check flapping on one host",
                    "edge-01 failed 2 of the last 20 health probes then recovered. "
                    + "11 other hosts in the pool are healthy. Traffic unaffected.", "critical"),
            new Sample("orders-db", "pg-primary", "Disk usage 91% on primary",
                    "Data volume at 91%, growing about 2% per day. WAL archiving is current. "
                    + "At this rate roughly 4 days of headroom remain.", "critical"),
            new Sample("search-api", "search-5d3", "p99 latency above target",
                    "p99 780ms against a 500ms target for 20 minutes. p50 unchanged at 40ms. "
                    + "Coincides with a reindex job.", "warning"),
            new Sample("auth-service", "auth-9b2", "TLS certificate expires in 26 days",
                    "Certificate for auth.internal expires 2026-10-05. Automated renewal last "
                    + "succeeded 60 days ago.", "info"),
            new Sample("inventory-svc", "inv-3c8", "All requests failing after deploy",
                    "100% of requests returning 500 since the v2.14.0 rollout 4 minutes ago. "
                    + "Readiness probe failing. Rollback not yet started.", "warning"),
            new Sample("notifications", "notif-1", "Retry succeeded after one timeout",
                    "A single call to the email provider timed out and succeeded on retry. "
                    + "No user impact.", "critical"));

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    @Value("${klaxon.topics.raw}")
    private String rawTopic;

    public SyntheticGenerator(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${klaxon.generator.interval-ms:8000}")
    public void emit() {
        Sample s = SAMPLES.get(ThreadLocalRandom.current().nextInt(SAMPLES.size()));
        AlertEvent event = new AlertEvent(
                UUID.randomUUID(), UUID.randomUUID(),
                "synthetic", s.service(), s.host(), s.title(), s.body(), s.reported(), Instant.now());
        try {
            kafka.send(rawTopic, event.alertId().toString(), json.writeValueAsString(event));
            log.debug("Emitted synthetic alert for {}", s.service());
        } catch (Exception e) {
            log.warn("Could not emit synthetic alert: {}", e.getMessage());
        }
    }
}
