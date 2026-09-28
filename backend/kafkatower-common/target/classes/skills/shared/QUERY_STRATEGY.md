# Query Strategy

Calls you're about to make are rarely independent — one call's result often tells you whether a
later one is worth making at all, or with what parameters. Before firing a batch of tool calls,
ask yourself:

- **Is there a precondition check?** Some signals only mean something if a more basic one already
  holds (e.g. a per-broker or per-topic value is only informative if the thing reporting it —
  the exporter, the broker, the index — is actually up and reachable). Check the precondition
  first; if it fails, the dependent calls will predictably come back empty or fail too — stop
  there and say what you found, instead of spending calls to confirm the obvious.
- **Does an empty, zero, or "not found" result mean "healthy" or "no data"?** The same result
  shape can mean two opposite things depending on what you already know. Decide which one applies
  before deciding whether to move on, narrow further, or stop.
- **Would the next call's outcome already be implied by this one?** If so, don't make it — reason
  from what you already have instead of collecting confirmations nobody asked for.
- **Should the next call's parameters change based on what you just saw?** (a filter to add, a
  broader/narrower selector, a different name or ID) — adapt rather than running a pre-planned
  batch unchanged regardless of what came back.

Any step-by-step workflow or playbook this skill gives you is a reference catalog of what to
check and how — not a fixed script to run start-to-finish on every request. Build your own
sequence from it based on what the question actually needs and what earlier results tell you,
stopping as soon as you have enough to answer.
