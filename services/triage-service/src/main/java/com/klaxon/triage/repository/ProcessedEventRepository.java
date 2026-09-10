package com.klaxon.triage.repository;

import com.klaxon.triage.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {

    /** With @IdClass the id fields sit directly on the entity, so no "Id" prefix here. */
    boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);
}
