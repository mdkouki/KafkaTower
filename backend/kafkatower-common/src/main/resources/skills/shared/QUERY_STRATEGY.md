# Query Strategy

Calls depend on each other. Before a batch, ask:
- Precondition? Exporter, broker, index must be up before dependent values mean anything. Check
  first. Fails → stop, say what found.
- Empty/zero/"not found" = healthy or no data? Decide before moving on.
- Next call already implied by this result? Skip it.
- Next call's parameters change given what just came back? Adapt. No pre-planned batch.

Playbooks and workflows = catalog of what to check, not script. Build own sequence. Stop when
enough to answer.
