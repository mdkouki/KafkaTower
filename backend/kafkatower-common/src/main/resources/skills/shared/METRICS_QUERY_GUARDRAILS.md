# Metrics Query Guardrails

Apply to all metrics tools (`metrics`, `query`, `query_range`, `labels`, `label_values`).

- **Discovery cap**: `metrics` at most once per response.
- **Discovery gate**: planned name absent from discovery result → not exported for this cluster.
  Do not query "just in case". Report unavailable. Widen pattern and rediscover only if pattern
  itself was too narrow (still once per response).
- **Circuit breaker**: tool call that *errors* (timeout, refused, non-success) ≠ call that returns
  empty. Error = backend broken. 3 metrics-tool errors this turn (any mix) → stop all metrics
  calls. Say plainly metrics store unreachable/erroring, name failed calls, so caller falls back.
  Never report outage as "no data".
