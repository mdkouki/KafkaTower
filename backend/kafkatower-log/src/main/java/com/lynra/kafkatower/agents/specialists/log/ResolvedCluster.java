package com.lynra.kafkatower.agents.specialists.log;

import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientProvider;

/** {@code note}, when non-null, must be surfaced to the user — it means the picked cluster wasn't a clean match. */
public record ResolvedCluster(OpenSearchClientProvider provider, String note) {}
