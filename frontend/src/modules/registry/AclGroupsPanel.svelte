<script>
  import { createEventDispatcher } from 'svelte';

  export let title;
  export let grants = []; // [{ pattern, prefixed }]
  export let color; // css color value, e.g. 'var(--color-info)'
  export let clusterName;

  const dispatch = createEventDispatcher();

  const STATE_COLORS = {
    Stable: 'var(--color-success)',
    Empty: 'var(--color-danger)',
    Dead: 'var(--color-danger)',
    PreparingRebalance: 'var(--color-warning)',
    CompletingRebalance: 'var(--color-warning)',
  };

  function stateColor(state) {
    return STATE_COLORS[state] ?? 'var(--color-text-muted)';
  }

  function keyOf(g) {
    return `${g.pattern}|${g.prefixed}`;
  }

  // Cache is keyed by cluster+grant so switching clusters or re-opening the same principal
  // doesn't re-fetch, but a different cluster's groups are never shown under a stale key.
  let matchesByGrant = {};

  async function loadMatches(g) {
    const key = keyOf(g);
    matchesByGrant = { ...matchesByGrant, [key]: { loading: true, error: null, data: null } };
    try {
      const res = await fetch(`/api/registry/acl-users/detail/groups?clusterName=${encodeURIComponent(clusterName)}&pattern=${encodeURIComponent(g.pattern)}&prefixed=${g.prefixed}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      matchesByGrant = { ...matchesByGrant, [key]: { loading: false, error: null, data } };
    } catch (e) {
      matchesByGrant = { ...matchesByGrant, [key]: { loading: false, error: e.message, data: null } };
    }
  }

  $: if (clusterName) {
    for (const g of grants) {
      if (!matchesByGrant[keyOf(g)]) loadMatches(g);
    }
  }

  function viewGroup(groupId) {
    dispatch('viewgroup', groupId);
  }
</script>

<section class="panel" style="--panel-color: {color}">
  <header class="panel-head">
    <span class="panel-indicator"></span>
    <h2 class="panel-title">{title}</h2>
    <span class="panel-count">{grants.length}</span>
  </header>

  {#if grants.length === 0}
    <p class="panel-empty">Nothing here yet</p>
  {:else}
    <ul class="grant-list">
      {#each grants as g (keyOf(g))}
        <li class="grant-row">
          <div class="grant-head">
            <span class="grant-pattern">{g.pattern}</span>
            <span class="grant-badge" class:prefix={g.prefixed}>{g.prefixed ? 'Prefix' : 'Literal'}</span>
          </div>

          {#if !matchesByGrant[keyOf(g)] || matchesByGrant[keyOf(g)].loading}
            <p class="grant-status">Resolving live groups…</p>
          {:else if matchesByGrant[keyOf(g)].error}
            <p class="grant-status error">Could not reach Kafka: {matchesByGrant[keyOf(g)].error}</p>
          {:else if matchesByGrant[keyOf(g)].data.length === 0}
            <p class="grant-status">No matching group exists yet</p>
          {:else}
            <ul class="match-list">
              {#each matchesByGrant[keyOf(g)].data as m (m.groupId)}
                <li>
                  <button class="match-row" on:click={() => viewGroup(m.groupId)} title="View live state">
                    <span class="state-dot" style="--dot-color: {stateColor(m.state)}"></span>
                    <span class="match-id">{m.groupId}</span>
                    <span class="match-state">{m.state}</span>
                  </button>
                </li>
              {/each}
            </ul>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .panel {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    display: flex;
    flex-direction: column;
    min-height: 64px;
    overflow: hidden;
    position: relative;
  }

  .panel-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
  }

  .panel-indicator {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--panel-color, var(--color-accent));
    flex-shrink: 0;
  }

  .panel-title {
    font-size: 12.5px;
    font-weight: 600;
    color: var(--color-text-primary);
    flex: 1;
    min-width: 0;
  }

  .panel-count {
    font-family: var(--font-mono);
    font-size: 11px;
    color: var(--color-text-muted);
    background: var(--color-surface-sunken);
    border-radius: var(--radius-full);
    padding: 1px 8px;
  }

  .panel-empty {
    padding: 16px 14px;
    font-size: 12.5px;
    color: var(--color-text-muted);
  }

  .grant-list {
    list-style: none;
    overflow-y: auto;
    flex: 1;
    min-height: 0;
  }

  .grant-row {
    padding: 10px 14px;
    border-bottom: 1px solid var(--color-border);
  }
  .grant-row:last-child { border-bottom: none; }

  .grant-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 5px;
  }

  .grant-pattern {
    font-family: var(--font-mono);
    font-size: 12.5px;
    color: var(--color-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }

  .grant-badge {
    flex-shrink: 0;
    font-size: 10.5px;
    font-weight: 600;
    padding: 2px 8px;
    border-radius: var(--radius-full);
    background: var(--color-surface-sunken);
    color: var(--color-text-muted);
  }
  .grant-badge.prefix {
    background: rgba(245, 166, 35, 0.14);
    color: var(--color-warning);
  }

  .grant-status {
    font-size: 11.5px;
    color: var(--color-text-muted);
    padding: 2px 0 2px 2px;
  }
  .grant-status.error { color: var(--color-danger); }

  .match-list {
    list-style: none;
    margin-top: 4px;
  }

  .match-row {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 5px 8px;
    background: none;
    border: none;
    cursor: pointer;
    text-align: left;
    border-radius: var(--radius-sm);
    transition: background var(--duration-fast) ease;
  }
  .match-row:hover { background: var(--color-surface-hover); }
  .match-row:focus-visible { outline: 1px solid var(--color-accent); outline-offset: -1px; }

  .state-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--dot-color);
    flex-shrink: 0;
  }

  .match-id {
    font-family: var(--font-mono);
    font-size: 12px;
    color: var(--color-text-primary);
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .match-state {
    font-size: 10.5px;
    color: var(--color-text-muted);
    flex-shrink: 0;
  }
</style>
