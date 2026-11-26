---
name: kafka-investigation-report
description: "Use this skill to write the final Kafka Investigation Report once the admin findings and metric correlation are gathered: the exact template (scope, time range, status, findings, root cause) and the rules for compressing evidence into it. Use it for the last step of an investigation, never to gather data."
---

# Kafka Investigation Report

Template exactly. No extra narrative. No raw metric payloads or full partition tables.

```
## Kafka Investigation Report
**Scope**: <cluster | broker X | topic Y | group Z | principal P>
**Time range**: <range used>

### Status: Healthy / Degraded / Critical

### Findings
1. [Admin] <resource>: <compressed finding, e.g. "2/12 partitions ISR < RF">
2. [Metric] <metric>: <scalar or trend, e.g. "lag=45k, rate=+200/s growing">

### Root Cause (if identified)
<1-3 sentences>
```

- Every finding comes from the evidence provided. Do not invent a finding that is not there.
- Tag each finding `[Admin]` or `[Metric]` by where it came from.
- No root cause identified → leave the section out rather than guessing.
