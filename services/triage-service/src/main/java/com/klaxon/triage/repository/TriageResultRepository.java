package com.klaxon.triage.repository;

import com.klaxon.triage.api.dto.TriagedAlertView;
import com.klaxon.triage.domain.Severity;
import com.klaxon.triage.domain.TriageResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TriageResultRepository extends JpaRepository<TriageResult, UUID> {

    String SELECT_VIEW = """
            select new com.klaxon.triage.api.dto.TriagedAlertView(
                a.id, a.service, a.host, a.title, a.rawSeverity,
                t.severity, t.category, t.summary, t.hypothesis, t.confidence,
                t.decidedBy, t.model, t.latencyMs, t.similarAlertId, t.similarity,
                a.occurredAt, t.decidedAt)
            from TriageResult t join Alert a on a.id = t.alertId
            """;

    @Query(SELECT_VIEW + " order by t.decidedAt desc")
    List<TriagedAlertView> findRecent(Pageable pageable);

    @Query(SELECT_VIEW + " where t.severity = :severity order by t.decidedAt desc")
    List<TriagedAlertView> findRecentBySeverity(@Param("severity") Severity severity, Pageable pageable);

    @Query(SELECT_VIEW + " where a.id = :alertId")
    List<TriagedAlertView> findViewByAlertId(@Param("alertId") UUID alertId);

    @Query("select t.severity, count(t) from TriageResult t group by t.severity")
    List<Object[]> countBySeverity();
}
