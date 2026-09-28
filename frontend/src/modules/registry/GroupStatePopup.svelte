<script>
  import { createEventDispatcher } from 'svelte';
  import { fade, scale } from 'svelte/transition';
  import { motionParams } from '../../core/motion.js';

  export let group;
  export let clusterName;

  const dispatch = createEventDispatcher();

  let data = null;
  let loading = true;
  let error = null;

  const STATE_COLORS = {
    Stable: 'var(--color-success)',
    Empty: 'var(--color-text-muted)',
    Dead: 'var(--color-danger)',
    PreparingRebalance: 'var(--color-warning)',
    CompletingRebalance: 'var(--color-warning)',
  };

  function stateColor(state) {
    return STATE_COLORS[state] ?? 'var(--color-text-muted)';
  }

  async function load() {
    loading = true;
    error = null;
    try {
      const res = await fetch(`/api/groups/${encodeURIComponent(group)}?clusterName=${encodeURIComponent(clusterName)}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      data = await res.json();
    } catch (e) {
      error = e.message;
    } finally {
      loading = false;
    }
  }

  load();

  function onOverlayClick(e) {
    if (e.target === e.currentTarget) dispatch('close');
  }

  function handleKeydown(e) {
    if (e.key === 'Escape') dispatch('close');
  }
</script>

<svelte:window on:keydown={handleKeydown} />

<!-- svelte-ignore a11y-click-events-have-key-events -->
<!-- svelte-ignore a11y-no-static-element-interactions -->
<div class="overlay" on:click={onOverlayClick} transition:fade={motionParams({ duration: 180 })}>
  <div
    class="popup"
    role="dialog"
    aria-modal="true"
    aria-label="Consumer group state"
    transition:scale={motionParams({ start: 0.96, duration: 200 })}
  >
    <div class="popup-header">
      <div class="popup-title">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/>
          <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>
        </svg>
        <code>{group}</code>
      </div>
      <button class="close-btn" on:click={() => dispatch('close')} aria-label="Close">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true">
          <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      </button>
    </div>

    <div class="popup-body">
      {#if loading}
        <div class="centered">
          <div class="spinner"></div>
          <span>Fetching live state…</span>
        </div>

      {:else if error}
        <div class="centered error">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="var(--color-danger)" stroke-width="1.5" aria-hidden="true">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <span>Could not reach Kafka: {error}</span>
        </div>

      {:else}
        <div class="meta-row">
          <span class="state-badge" style="--badge-color: {stateColor(data.state)}">
            <span class="state-dot" style="--dot-color: {stateColor(data.state)}"></span>
            {data.state}
          </span>
          <span class="lag-total">Total lag <strong>{data.totalLag.toLocaleString()}</strong></span>
        </div>

        {#if data.members.length > 0}
          <section class="section">
            <h3 class="section-title">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/>
              </svg>
              Members ({data.members.length})
            </h3>
            <div class="members-list">
              {#each data.members as m}
                <div class="member-card">
                  <div class="member-header">
                    <code class="member-client">{m.clientId}</code>
                    <span class="member-host">{m.host}</span>
                  </div>
                  {#if m.assignedPartitions.length > 0}
                    <div class="partition-chips">
                      {#each m.assignedPartitions as p}
                        <span class="partition-chip">{p}</span>
                      {/each}
                    </div>
                  {/if}
                </div>
              {/each}
            </div>
          </section>
        {/if}

        {#if data.partitionLags.length > 0}
          <section class="section">
            <h3 class="section-title">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>
              </svg>
              Partition lag
            </h3>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Topic / partition</th>
                    <th class="num">Committed</th>
                    <th class="num">End</th>
                    <th class="num">Lag</th>
                  </tr>
                </thead>
                <tbody>
                  {#each data.partitionLags as pl}
                    <tr class:high-lag={pl.lag > 1000}>
                      <td><code>{pl.topic}<span class="partition-num">-{pl.partition}</span></code></td>
                      <td class="num">{pl.committedOffset.toLocaleString()}</td>
                      <td class="num">{pl.endOffset.toLocaleString()}</td>
                      <td class="num lag-cell" class:nonzero={pl.lag > 0}>{pl.lag.toLocaleString()}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          </section>
        {/if}
      {/if}
    </div>
  </div>
</div>

<style>
  .overlay {
    position: fixed;
    inset: 0;
    background: rgba(4, 6, 10, 0.6);
    backdrop-filter: blur(var(--glass-blur));
    z-index: 200;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 20px;
  }

  .popup {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-lg);
    width: 560px;
    max-width: 100%;
    max-height: 84vh;
    display: flex;
    flex-direction: column;
    position: relative;
  }

  .popup-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 18px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
  }

  .popup-title {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--color-text-muted);
    font-size: 13px;
    font-weight: 600;
  }

  .popup-title code {
    font-family: var(--font-mono);
    color: var(--color-text-primary);
    font-size: 13px;
  }

  .close-btn {
    padding: 5px;
    border: none;
    background: none;
    cursor: pointer;
    border-radius: var(--radius-sm);
    color: var(--color-text-muted);
    display: flex;
    align-items: center;
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease;
  }
  .close-btn:hover { background: var(--color-surface-hover); color: var(--color-text-primary); }
  .close-btn:focus-visible { outline: 1px solid var(--color-accent); }

  .popup-body {
    overflow-y: auto;
    flex: 1;
    padding: 0 0 16px;
  }

  .centered {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    padding: 40px 20px;
    color: var(--color-text-muted);
    font-size: 13px;
  }

  .centered.error { color: var(--color-danger); }

  .spinner {
    width: 26px;
    height: 26px;
    border: 2.5px solid var(--color-border);
    border-top-color: var(--color-accent);
    border-radius: 50%;
    animation: spin 0.75s linear infinite;
  }

  @keyframes spin { to { transform: rotate(360deg); } }
  @media (prefers-reduced-motion: reduce) {
    .spinner { animation-duration: 2s; }
  }

  .meta-row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px 18px;
    border-bottom: 1px solid var(--color-border);
  }

  .state-badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    font-weight: 600;
    padding: 4px 10px;
    border-radius: var(--radius-full);
    background: var(--color-surface-sunken);
    color: var(--badge-color);
  }

  .state-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--dot-color);
  }

  .lag-total {
    font-size: 12.5px;
    color: var(--color-text-muted);
    margin-left: auto;
  }

  .lag-total strong {
    color: var(--color-text-primary);
    font-weight: 700;
    font-family: var(--font-mono);
  }

  .section {
    padding: 16px 18px 0;
    border-bottom: 1px solid var(--color-border);
    padding-bottom: 16px;
  }
  .section:last-child { border-bottom: none; }

  .section-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 11.5px;
    font-weight: 600;
    color: var(--color-text-muted);
    margin-bottom: 10px;
  }

  .members-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .member-card {
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    overflow: hidden;
  }

  .member-header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 7px 10px;
    background: var(--color-surface-sunken);
  }

  .member-client {
    font-family: var(--font-mono);
    font-size: 11.5px;
    color: var(--color-text-primary);
    font-weight: 600;
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .member-host {
    font-family: var(--font-mono);
    font-size: 11px;
    color: var(--color-text-muted);
    flex-shrink: 0;
  }

  .partition-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    padding: 8px 10px;
  }

  .partition-chip {
    font-size: 10.5px;
    font-family: var(--font-mono);
    background: var(--color-accent-light);
    color: var(--color-accent);
    padding: 1px 7px;
    border-radius: var(--radius-full);
  }

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

  .partition-num { color: var(--color-text-muted); }

  .num { text-align: right; font-variant-numeric: tabular-nums; }

  .lag-cell { color: var(--color-text-muted); font-family: var(--font-mono); }
  .lag-cell.nonzero { color: var(--color-warning); font-weight: 700; }
</style>
