# Klaxon — streaming alert triage with an LLM in the pipeline

Monitoring alerts arrive on Kafka. A consumer enriches each one with Google Gemini —
severity, category, a root-cause hypothesis — finds the nearest previously-seen
incident by vector similarity, and pushes the verdict to a live Angular dashboard.

The AI is a **pipeline stage**, not a chat box. Alerts are triaged whether or not
anyone has the page open.

```
 alert sources ──HTTP──►  POST /api/v1/alerts ──► Kafka: alerts.raw  (3 partitions, key = alertId)
 (or the built-in                                        │
  synthetic generator)                                   ▼
                                          triage-service  @KafkaListener
                                            │
                                            ├─ processed_events         idempotent: a redelivery is a no-op
                                            ├─ Gemini 3.5 Flash         severity + category + hypothesis,
                                            │                           constrained by a response schema
                                            ├─ RuleBasedTriage          used when the model is unavailable
                                            ├─ gemini-embedding-001     ──► pgvector
                                            └─ nearest prior alert      "this looks like the one from Tuesday"
                                            │
                                            ▼   (after commit — never inside the transaction)
                                  Kafka: alerts.triaged ──► SSE ──► Angular 18 dashboard
                                            │
                                            └─ 3 failures ──► alerts.raw.DLT with the reason in headers
```

## Why this is not another RAG chatbot

| Decision | Reason |
|---|---|
| LLM inside a Kafka consumer | Triage happens on arrival. There is no user waiting, so latency budget buys better reasoning. |
| Structured output via response schema | The reply is parsed as JSON against a fixed schema, not scraped out of prose. |
| A deterministic fallback that always exists | No key, spent quota, or malformed reply degrades to rule-based triage. `decided_by` is stored per verdict, so the UI never implies a model was involved when it wasn't. |
| Rate limiter sized to the free tier | A burst of alerts would otherwise exhaust the per-minute quota in seconds and silently degrade the whole batch. |
| Verdict cache keyed on fingerprint | A flapping check costs one model call, not one per firing. |
| Publish after commit | Sending inside the transaction is a dual write — the verdict could be published before it is durable. |
| pgvector for recall | "Have we seen this before" is a similarity question, so it is answered with embeddings rather than string matching. |

## Run it

Prereqs: Docker Desktop, JDK 17, Maven, Node 20 (Angular 18 does not support Node 24).

```bash
docker compose -f deploy/docker-compose.yml up -d          # Postgres+pgvector, Redpanda, Console

export GEMINI_API_KEY=...                                  # free key: aistudio.google.com/apikey
cd services/triage-service && mvn spring-boot:run          # :8090

cd web/klaxon-ui && npm start                              # :4300
```

Without `GEMINI_API_KEY` everything still runs — every verdict is simply
`decided_by: FALLBACK` and there is no similarity recall. That is the degraded
path working as designed, not a broken setup.

Turn on the synthetic feed to watch it work:

```bash
KLAXON_GENERATOR_ENABLED=true mvn spring-boot:run
```

Or send one by hand:

```bash
curl -X POST localhost:8090/api/v1/alerts -H 'Content-Type: application/json' -d '{
  "source":"prometheus","service":"checkout-api","host":"checkout-7f9c",
  "title":"Sustained 5xx on POST /checkout",
  "body":"Error rate 34% over 6 minutes. Upstream payment-gateway returning 503.",
  "severity":"warning"}'
```

Returns `202 Accepted` — the verdict arrives on the dashboard a moment later.

* Dashboard — http://localhost:4300
* Redpanda Console — http://localhost:8086
* Metrics — http://localhost:8090/actuator/prometheus (`klaxon_triaged_total{decided_by=}`, `klaxon_prompt_tokens_total`)

Ports are deliberately offset so this can run alongside another local Kafka stack.

## The demo

| Try | What you see |
|---|---|
| Watch the synthetic feed | Four of the eight samples report a severity the detail does not support. Gemini overrides them and the dashboard flags the disagreement. |
| Send the same alert twice | One verdict, one row. The second is skipped on `processed_events`. |
| Unset `GEMINI_API_KEY` and restart | Every row switches to `FALLBACK`. The pipeline never stops. |
| Send the flapping health check | Reported `critical`, triaged `LOW` — one host of twelve, no traffic impact. |
| Produce garbage to `alerts.raw` | Three retries, then `alerts.raw.DLT` with the failure reason in the headers. |

## Layout

```
services/triage-service   Spring Boot 3.3, Spring Kafka, JPA, Flyway, pgvector via JdbcTemplate
web/klaxon-ui             Angular 18 standalone, SSE live feed
deploy/                   docker-compose: Postgres+pgvector, Redpanda, Console
```

## Honest notes

* One service, not two. The Kafka pipeline is real — it produces, consumes, and
  dead-letters — but ingest and triage share a process. Splitting them is the
  natural next step, not a claim this repo already makes.
* No authentication. The API is open so the demo runs without an identity provider.
* The rate limiter is in-process, so it is correct for one replica. Multiple
  replicas would need a shared limiter such as Redis.
* Similarity uses a fixed cosine cutoff. A real deployment would tune it against
  labelled incident pairs rather than a constant.
