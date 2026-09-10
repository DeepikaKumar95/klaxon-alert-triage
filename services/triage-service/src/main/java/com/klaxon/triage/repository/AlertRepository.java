package com.klaxon.triage.repository;

import com.klaxon.triage.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    /** Most recent prior firing of the same condition, used for dedupe and recall. */
    Optional<Alert> findFirstByFingerprintOrderByReceivedAtDesc(String fingerprint);

    long countByFingerprint(String fingerprint);
}
