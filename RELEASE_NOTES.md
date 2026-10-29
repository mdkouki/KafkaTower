## KafkaTower 1.0.0

First release. KafkaTower is a console for Kafka operations: see who can touch what, which topics are alive and whether your consumers keep up, or just ask a team of specialist agents.

### Highlights

- **Assistant: an agent team.** A root orchestrator calls specialists as tools, in any order, and cross-checks its own draft before replying.
  - **KafkaInvestigator**: live cluster state through read-only AdminClient tools (brokers, topics, offsets, consumer groups and lag, ACLs, quotas).
  - **KafkaMetricsInspector**: historical metrics from any Prometheus-compatible store (VictoriaMetrics, Prometheus, Thanos, Mimir).
  - **KafkaLogInspector**: logs and traces from OpenSearch.
  - **KafkaRegistryInspector**: who produces to or consumes from a topic, what a service can access, and what depends on what.
  - Every draft goes through an independent completeness review, and a guardrail blocks secret leakage.
- **Registry.** Per-identity **Details** (consume/produce topics, consumer groups, transactional IDs) and a **Dependencies** graph of upstream and downstream principals, built from the cluster's ACLs.
- **Topics.** Properties, producers, consumers and consumer-group lag per topic.
- **Topic content search** (admin only). Search records by key, header and value across partitions, decoded as String, JSON, **Avro** or **Protobuf** with a schema stored per topic. No schema registry needed; Confluent-framed Avro is understood.
- **Settings panel.** Configure the LLM, Kafka clusters (None, mTLS or SASL), OpenSearch and the metrics backend from the UI. Each tab verifies against the real service before saving.
- **Multiple clusters**, sign-in with roles, and a demo environment (`compose.yml`) with Kafka, AKHQ, VictoriaMetrics, OpenSearch and Phoenix tracing.
- **Test data.** `scripts/produce-test-messages.py` writes random Avro or Protobuf messages to a topic (Python 3 standard library only).

### Run it

Requires Java 21 and PostgreSQL (or the `h2` profile for a single-node setup).

```bash
java -jar kafkatower-backend-1.0.0.jar
# or, without PostgreSQL:
java -jar kafkatower-backend-1.0.0.jar --spring.profiles.active=h2
```

Open http://localhost:8089 and sign in with the development defaults (`admin` / `admin123`, `user` / `user123`). **Change them before exposing the app to anyone.** See the [README](README.md) for the full setup.

### Notes

- The console works without an LLM; the assistant starts once an admin completes the LLM settings.
- Secrets saved in the Settings panel are stored in plain text in the application database. Restrict access to it.

### License

Apache License 2.0. If you reuse the code, keep the `LICENSE` and `NOTICE` files with your copy and credit the original project.
