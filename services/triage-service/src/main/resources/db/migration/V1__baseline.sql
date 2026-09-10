CREATE EXTENSION IF NOT EXISTS vector;

-- One row per alert we have ever seen. fingerprint is the natural dedupe key:
-- the same service+title+host firing twice is the same incident, not two.
CREATE TABLE alerts (
    id             UUID PRIMARY KEY,
    fingerprint    TEXT        NOT NULL,
    source         TEXT        NOT NULL,
    service        TEXT        NOT NULL,
    host           TEXT,
    title          TEXT        NOT NULL,
    body           TEXT        NOT NULL,
    raw_severity   TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_alerts_fingerprint ON alerts (fingerprint);
CREATE INDEX ix_alerts_received    ON alerts (received_at DESC);

-- The triage verdict, kept separate from the alert so a re-triage never mutates
-- the original signal. decided_by records whether the LLM or the fallback decided.
CREATE TABLE triage_results (
    alert_id        UUID PRIMARY KEY REFERENCES alerts(id) ON DELETE CASCADE,
    severity        TEXT        NOT NULL,
    category        TEXT        NOT NULL,
    summary         TEXT        NOT NULL,
    hypothesis      TEXT,
    confidence      REAL,
    decided_by      TEXT        NOT NULL,
    model           TEXT,
    prompt_tokens   INT,
    output_tokens   INT,
    latency_ms      INT,
    similar_alert_id UUID REFERENCES alerts(id),
    similarity      REAL,
    decided_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_triage_severity ON triage_results (severity, decided_at DESC);

-- 768 matches text-embedding-004. Stored separately because an alert can exist
-- before it has been embedded (LLM down, quota exhausted).
CREATE TABLE alert_embeddings (
    alert_id   UUID PRIMARY KEY REFERENCES alerts(id) ON DELETE CASCADE,
    embedding  vector(768) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Consumer idempotency: the marker is written in the same transaction as the
-- business update, so a redelivery is a no-op rather than a second triage.
CREATE TABLE processed_events (
    event_id       UUID        NOT NULL,
    consumer_group TEXT        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer_group)
);
