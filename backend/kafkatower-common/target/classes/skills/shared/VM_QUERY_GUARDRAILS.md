# VictoriaMetrics Query Guardrails

Shared by every agent that queries the VictoriaMetrics MCP (`metrics`, `query`, `query_range`,
`labels`, `label_values`) — `KafkaMetricsInspector` and `KafkaInvestigator` hit the same backend
independently, so these rules live here once instead of drifting between two copies.

- **Discovery cap**: call the `metrics` discovery tool at most once per response.
- **Discovery gate**: if a metric name you planned to query is absent from that discovery result,
  do not query it anyway "just in case" — an absent name means it isn't exported for this cluster,
  so querying it can only return empty. Treat it as unavailable and say so directly; only widen
  the discovery pattern and re-discover (still within the once-per-response cap) if you suspect
  the pattern itself was too narrow rather than the metric genuinely not existing.
- **Failure circuit breaker**: a tool call that **errors** (timeout, connection refused,
  non-success response from VictoriaMetrics) is not the same signal as one that **succeeds with
  an empty result** — an error means the backend itself is unreachable or broken, not that a
  metric isn't exported (that's the Discovery gate above, a different case). If 3 separate
  VictoriaMetrics tool calls (`metrics`, `query`, `query_range`, `labels`, `label_values` — any
  mix) error out in this turn, stop issuing any further VictoriaMetrics tool calls for the rest
  of this response — do not keep retrying or trying different queries hoping one succeeds. State
  plainly and explicitly in your answer that VictoriaMetrics appears unreachable or erroring right
  now (name which calls failed), so the caller knows to fall back to another source of truth
  instead of reading this as "no data exists for this cluster." Never fold this into a quiet "no
  data found" — an outage and a genuinely empty metric are different findings and must be
  reported differently.
