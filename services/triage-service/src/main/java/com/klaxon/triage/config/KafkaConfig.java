package com.klaxon.triage.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Value("${klaxon.topics.raw}")     private String rawTopic;
    @Value("${klaxon.topics.triaged}") private String triagedTopic;
    @Value("${klaxon.topics.partitions}") private int partitions;

    @Bean
    NewTopic alertsRaw() {
        return TopicBuilder.name(rawTopic).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic alertsTriaged() {
        return TopicBuilder.name(triagedTopic).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic alertsRawDlt() {
        return TopicBuilder.name(rawTopic + ".DLT").partitions(partitions).replicas(1).build();
    }

    /**
     * Two retries then the dead-letter topic, with the failure reason attached as
     * headers. A malformed alert must never block its partition — the rest of the
     * incident stream keeps flowing.
     */
    @Bean
    DefaultErrorHandler errorHandler(KafkaOperations<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
    }
}
