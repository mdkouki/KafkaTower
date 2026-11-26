---
name: rich-output-formatting
description: "Use this skill before writing a final answer that contains a Mermaid diagram or a Chart.js chart, for example a dependency chain, a topology or a trend over time. Covers valid Mermaid label quoting, no literal line breaks in labels, the chart JSON shape, and the rule that every node, edge and data point must come from specialist output."
---

# Rich Output Formatting

Your final answer is rendered as Markdown with syntax-highlighted code blocks, plus two
special fenced-block types the UI turns into an interactive diagram or chart. When a diagram or
chart would explain the finding more clearly than prose alone — a dependency chain, a topology,
a trend over time — use one; don't default to plain text out of caution. Plenty of answers are a
fact or two that prose states more directly than any diagram could, and those stay plain
text/Markdown — the test is which form actually explains the finding better, not which is safer.

- ` ```mermaid ` — for relationships, flows, or topologies (e.g. "which services produce to
  which topics", "trace this consumer group's dependency chain"). Use valid Mermaid syntax
  (`flowchart`, `sequenceDiagram`, `erDiagram`, etc.). Every node/edge must come from data a
  specialist actually returned — never invent a relationship to fill out the diagram.
  - **Quote every node label that contains anything beyond plain words/numbers** — parentheses,
    colons, quotes, `#`, commas, or any punctuation. In flowchart syntax `(` and `)` are node-shape
    delimiters (`id(text)` means something different from a label containing literal parens), so
    an unquoted label like `A[topic-x (Owned by: team-y)]` is a parse error, not a rendering
    choice. Always wrap that label in double quotes instead: `A["topic-x (Owned by: team-y)"]`.
  - **Never put a literal line break inside a node label.** A label can only span one line in the
    source; use `<br/>` where you want a visual line break, not an actual newline character.
- ` ```chart ` — for numeric/time-series data (e.g. throughput or lag over time, per-partition
  offsets, comparisons across topics/clusters) that a specialist already returned. The fenced
  content must be valid JSON matching Chart.js's constructor config: `{"type": "line", "data":
  {"labels": [...], "datasets": [{"label": "...", "data": [...]}]}}` (`type` can be `bar`,
  `line`, `pie`, etc.). Only chart numbers that actually came back from a specialist — never
  fabricate data points to make a nicer-looking chart.

Both are optional and additive: still explain the finding in prose, don't rely on the
diagram/chart to carry the answer alone.
