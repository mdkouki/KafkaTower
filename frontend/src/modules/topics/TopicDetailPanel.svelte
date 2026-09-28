<script>
  import { fly } from 'svelte/transition';
  import { motionParams } from '../../core/motion.js';
  import HudPanel from '../registry/HudPanel.svelte';

  export let detail; // { name, partitionCount, replicationFactor, internal, configs, producers, consumers }
  export let clusterName;

  let copiedTopic = false;
  let copiedTimeout;

  let activeIndex = 0;
  // A fresh topic always opens on the first card rather than remembering
  // whatever was on top for the previously selected topic.
  $: if (detail && detail.name) activeIndex = 0;

  $: categories = detail ? [
    { key: 'properties', title: 'Properties', color: 'var(--color-success)' },
    { key: 'producers', title: 'Producers', color: 'var(--color-warning)' },
    { key: 'consumers', title: 'Consumers', color: 'var(--color-accent)' },
    { key: 'groups', title: 'Groups & lag', color: 'var(--color-info)' },
  ] : [];

  $: configEntries = detail ? Object.entries(detail.configs || {}) : [];

  // Live consumer-group lag, fetched on demand per topic and cached across tab switches.
  let groupsCache = {}; // topicName -> { loading, error, data }
  $: groupsState = detail ? groupsCache[detail.name] : null;

  function loadGroupsIfNeeded() {
    if (!detail) return;
    const key = detail.name;
    if (groupsCache[key]) return;
    groupsCache = { ...groupsCache, [key]: { loading: true, error: null, data: null } };
    fetch(`/api/topics/detail/groups?clusterName=${encodeURIComponent(clusterName)}&topic=${encodeURIComponent(key)}`)
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(data => {
        groupsCache = { ...groupsCache, [key]: { loading: false, error: null, data } };
      })
      .catch(e => {
        groupsCache = { ...groupsCache, [key]: { loading: false, error: e.message, data: null } };
      });
  }

  function selectTab(i, key) {
    activeIndex = i;
    if (key === 'groups') loadGroupsIfNeeded();
  }

  async function copyTopicName() {
    try {
      await navigator.clipboard.writeText(detail.name);
    } catch (_) {
      return;
    }
    copiedTopic = true;
    clearTimeout(copiedTimeout);
    copiedTimeout = setTimeout(() => (copiedTopic = false), 1400);
  }
</script>

{#if detail}
  {#key detail.name}
    <div class="console" in:fly={motionParams({ y: 8, duration: 200 })}>
      <div class="identity">
        <div class="identity-icon" aria-hidden="true">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 4h16v4H4z"/><path d="M4 12h10"/><path d="M4 18h16"/>
          </svg>
        </div>
        <div class="identity-main">
          <span class="identity-name">{detail.name}</span>
          <span class="identity-cluster">{clusterName} · {detail.partitionCount} partitions</span>
        </div>
        <button class="copy-btn" class:copied={copiedTopic} on:click={copyTopicName}>
          {#if copiedTopic}
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
            Copied
          {:else}
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
            Copy
          {/if}
        </button>
      </div>

      <div class="tabs" role="tablist" aria-label="Topic category">
        {#each categories as cat, i}
          <button
            class="tab"
            class:active={i === activeIndex}
            style="--tab-color: {cat.color}"
            role="tab"
            aria-selected={i === activeIndex}
            on:click={() => selectTab(i, cat.key)}
          >
            {cat.title}
            {#if cat.key === 'producers'}<span class="tab-count">{detail.producers.length}</span>{/if}
            {#if cat.key === 'consumers'}<span class="tab-count">{detail.consumers.length}</span>{/if}
          </button>
        {/each}
      </div>

      <div class="stack">
        {#each categories as cat, i (cat.key)}
          <div class="stack-card" class:front={i === activeIndex} inert={i !== activeIndex}>
            {#if cat.key === 'properties'}
              <section class="panel props-panel">
                <header class="panel-head">
                  <span class="panel-indicator" style="--panel-color: var(--color-success)"></span>
                  <h2 class="panel-title">Properties</h2>
                </header>
                <div class="props-body">
                  <dl class="props-grid">
                    <dt>Partitions</dt><dd>{detail.partitionCount}</dd>
                    <dt>Replication factor</dt><dd>{detail.replicationFactor}</dd>
                    <dt>Internal</dt><dd>{detail.internal ? 'Yes' : 'No'}</dd>
                  </dl>
                  {#if configEntries.length > 0}
                    <h3 class="config-title">Configs (non-default)</h3>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>Key</th><th>Value</th></tr></thead>
                        <tbody>
                          {#each configEntries as [k, v]}
                            <tr><td><code>{k}</code></td><td>{v}</td></tr>
                          {/each}
                        </tbody>
                      </table>
                    </div>
                  {:else}
                    <p class="panel-empty">No non-default configs</p>
                  {/if}
                </div>
              </section>
            {:else if cat.key === 'producers'}
              <HudPanel title="Producers" items={detail.producers} color="var(--color-warning)" />
            {:else if cat.key === 'consumers'}
              <HudPanel title="Consumers" items={detail.consumers} color="var(--color-accent)" />
            {:else if cat.key === 'groups'}
              <section class="panel props-panel">
                <header class="panel-head">
                  <span class="panel-indicator" style="--panel-color: var(--color-info)"></span>
                  <h2 class="panel-title">Groups & lag</h2>
                </header>
                <div class="props-body">
                  {#if !groupsState || groupsState.loading}
                    <div class="centered">
                      <span class="loading loading-spinner loading-md text-info"></span>
                      <span>Fetching live consumer-group state…</span>
                    </div>
                  {:else if groupsState.error}
                    <div class="centered error">Could not reach Kafka: {groupsState.error}</div>
                  {:else if groupsState.data.length === 0}
                    <p class="panel-empty">No consumer group is currently subscribed to this topic</p>
                  {:else}
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>Group</th><th>State</th><th class="num">Lag</th></tr></thead>
                        <tbody>
                          {#each groupsState.data as g}
                            <tr class:high-lag={g.lag > 1000}>
                              <td><code>{g.groupId}</code></td>
                              <td>{g.state}</td>
                              <td class="num lag-cell" class:nonzero={g.lag > 0}>{g.lag.toLocaleString()}</td>
                            </tr>
                          {/each}
                        </tbody>
                      </table>
                    </div>
                  {/if}
                </div>
              </section>
            {/if}
          </div>
        {/each}
      </div>
    </div>
  {/key}
{/if}

<style>
  .console {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-lg);
    width: 100%;
    padding: 18px;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .identity {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
    background: var(--color-surface-sunken);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    flex-wrap: wrap;
  }

  .identity-icon {
    width: 34px;
    height: 34px;
    border-radius: var(--radius-md);
    background: var(--color-accent-light);
    color: var(--color-accent);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .identity-main {
    display: flex;
    flex-direction: column;
    min-width: 0;
    flex: 1;
  }

  .identity-name {
    font-family: var(--font-mono);
    font-size: 15px;
    font-weight: 600;
    color: var(--color-text-primary);
    word-break: break-word;
  }

  .identity-cluster {
    font-size: 11.5px;
    color: var(--color-text-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }

  .copy-btn {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 7px 12px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-surface);
    color: var(--color-text-secondary);
    font-size: 12px;
    font-weight: 500;
    cursor: pointer;
    flex-shrink: 0;
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease, border-color var(--duration-fast) ease;
  }
  .copy-btn:hover { background: var(--color-surface-hover); color: var(--color-text-primary); }
  .copy-btn:focus-visible { outline: 1px solid var(--color-accent); }
  .copy-btn.copied { color: var(--color-success); border-color: var(--color-success); }

  .tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .tab {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 7px 14px;
    background: var(--color-surface-sunken);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-full);
    color: var(--color-text-secondary);
    font-size: 12.5px;
    font-weight: 500;
    cursor: pointer;
    transition: background var(--duration-fast) ease, border-color var(--duration-fast) ease, color var(--duration-fast) ease;
  }
  .tab:hover { border-color: var(--tab-color); color: var(--color-text-primary); }
  .tab:focus-visible { outline: 1px solid var(--tab-color); outline-offset: 1px; }
  .tab.active {
    background: color-mix(in srgb, var(--tab-color) 14%, var(--color-surface-sunken));
    border-color: var(--tab-color);
    color: var(--color-text-primary);
  }

  .tab-count {
    font-family: var(--font-mono);
    font-size: 11px;
    opacity: 0.75;
  }

  .stack {
    position: relative;
    height: 420px;
  }

  .stack-card {
    position: absolute;
    inset: 0;
    opacity: 0;
    transition: opacity var(--duration-base) ease;
  }
  .stack-card:not(.front) { pointer-events: none; }
  .stack-card.front { opacity: 1; }
  .stack-card :global(.panel) { height: 100%; }

  @media (prefers-reduced-motion: reduce) {
    .stack-card { transition: none; }
  }

  @media (max-width: 640px) {
    .identity { padding: 10px 12px; }
    .identity-name { font-size: 13px; }
    .stack { height: 320px; }
  }

  /* Properties / Groups panels — same shell as HudPanel but with table/definition-list content. */
  .props-panel {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    display: flex;
    flex-direction: column;
    height: 100%;
    overflow: hidden;
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
    background: var(--panel-color);
    flex-shrink: 0;
  }

  .panel-title {
    font-size: 12.5px;
    font-weight: 600;
    color: var(--color-text-primary);
    flex: 1;
    min-width: 0;
  }

  .panel-empty {
    padding: 16px 14px;
    font-size: 12.5px;
    color: var(--color-text-muted);
  }

  .props-body {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 14px;
  }

  .props-grid {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 6px 16px;
    margin-bottom: 18px;
  }
  .props-grid dt {
    font-size: 11.5px;
    color: var(--color-text-muted);
  }
  .props-grid dd {
    font-family: var(--font-mono);
    font-size: 12.5px;
    color: var(--color-text-primary);
    margin: 0;
  }

  .config-title {
    font-size: 11px;
    font-weight: 600;
    color: var(--color-text-muted);
    margin-bottom: 8px;
  }

  .centered {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    padding: 30px 20px;
    color: var(--color-text-muted);
    font-size: 12.5px;
    text-align: center;
  }
  .centered.error { color: var(--color-danger); }

  .table-wrap {
    overflow-x: auto;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
  }

  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12px;
  }

  th {
    text-align: left;
    padding: 8px 10px;
    font-size: 10.5px;
    font-weight: 600;
    color: var(--color-text-muted);
    background: var(--color-surface-sunken);
    border-bottom: 1px solid var(--color-border);
  }

  td {
    padding: 7px 10px;
    border-bottom: 1px solid var(--color-border);
    color: var(--color-text-secondary);
  }

  tr:last-child td { border-bottom: none; }
  tr.high-lag { background: rgba(245, 166, 35, 0.06); }

  td code {
    font-family: var(--font-mono);
    font-size: 11.5px;
    color: var(--color-text-primary);
  }

  .num { text-align: right; font-variant-numeric: tabular-nums; }
  .lag-cell { color: var(--color-text-muted); font-family: var(--font-mono); }
  .lag-cell.nonzero { color: var(--color-warning); font-weight: 700; }
</style>
