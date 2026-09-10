package com.klaxon.triage.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * pgvector access. Deliberately JdbcTemplate rather than JPA: the vector type and
 * the <=> distance operator are Postgres-native and would only be obscured by an
 * ORM mapping.
 */
@Repository
public class EmbeddingRepository {

    private final JdbcTemplate jdbc;

    public EmbeddingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(UUID alertId, float[] embedding) {
        jdbc.update("""
                insert into alert_embeddings (alert_id, embedding)
                values (?, ?::vector)
                on conflict (alert_id) do update set embedding = excluded.embedding
                """, alertId, toVectorLiteral(embedding));
    }

    /**
     * Nearest previously-seen alert, excluding the one we just stored.
     * <=> is cosine distance, so similarity is 1 - distance.
     */
    public Optional<Match> findNearest(UUID excludeAlertId, float[] embedding, double maxDistance) {
        List<Match> hits = jdbc.query("""
                select alert_id, (embedding <=> ?::vector) as distance
                from alert_embeddings
                where alert_id <> ?
                order by embedding <=> ?::vector
                limit 1
                """,
                (rs, i) -> new Match(UUID.fromString(rs.getString("alert_id")), rs.getDouble("distance")),
                toVectorLiteral(embedding), excludeAlertId, toVectorLiteral(embedding));

        return hits.stream().filter(m -> m.distance() <= maxDistance).findFirst();
    }

    public long count() {
        Long n = jdbc.queryForObject("select count(*) from alert_embeddings", Long.class);
        return n == null ? 0 : n;
    }

    /** pgvector accepts a bracketed, comma-separated literal: [0.1,0.2,...] */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    public record Match(UUID alertId, double distance) {
        public float similarity() { return (float) (1.0 - distance); }
    }
}
