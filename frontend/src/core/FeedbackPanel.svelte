<script>
  import { fade, scale } from 'svelte/transition';
  import { motionParams } from './motion.js';

  export let onClose;

  let feedbacks = [];
  let loading = true;
  let error = null;
  let filter = 'ALL'; // ALL | UP | DOWN

  async function load() {
    loading = true;
    error = null;
    try {
      const res = await fetch('/api/feedback');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      feedbacks = await res.json();
    } catch (e) {
      error = e.message;
    } finally {
      loading = false;
    }
  }

  load();

  $: filtered = filter === 'ALL' ? feedbacks : feedbacks.filter(f => f.feedback === filter);

  function truncate(text, max = 120) {
    if (!text) return '';
    return text.length > max ? text.slice(0, max) + '…' : text;
  }

  function formatDate(iso) {
    return new Date(iso).toLocaleString(undefined, {
      month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
    });
  }

  $: upCount   = feedbacks.filter(f => f.feedback === 'UP').length;
  $: downCount = feedbacks.filter(f => f.feedback === 'DOWN').length;
</script>

<div class="overlay" on:click|self={onClose} transition:fade={motionParams({ duration: 180 })}>
  <div class="panel" transition:scale={motionParams({ start: 0.96, duration: 200 })}>
    <div class="panel-header">
      <div class="panel-title">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/>
          <path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>
        </svg>
        Chat Feedback
      </div>
      <button class="close-btn" on:click={onClose}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      </button>
    </div>

    <div class="panel-toolbar">
      <div class="stats">
        <span class="stat-chip up">{upCount} <svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/></svg></span>
        <span class="stat-chip down">{downCount} <svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor" style="transform:rotate(180deg)"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/></svg></span>
      </div>
      <div class="filter-tabs">
        {#each ['ALL','UP','DOWN'] as f}
          <button class="tab" class:active={filter === f} on:click={() => filter = f}>{f}</button>
        {/each}
      </div>
      <button class="reload-btn" on:click={load} title="Refresh">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <path d="M23 4v6h-6"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
        </svg>
      </button>
    </div>

    <div class="panel-body">
      {#if loading}
        <div class="centered"><span class="spinner"></span></div>
      {:else if error}
        <div class="centered error">{error}</div>
      {:else if filtered.length === 0}
        <div class="centered muted">No feedback yet.</div>
      {:else}
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>User</th>
              <th>Prompt</th>
              <th>Answer</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {#each filtered as fb (fb.id)}
              <tr>
                <td class="date">{formatDate(fb.createdAt)}</td>
                <td class="user">{fb.userId}</td>
                <td class="prompt" title={truncate(fb.userPrompt, 300)}>{fb.userPrompt}</td>
                <td class="answer" title={fb.agentAnswer}>{truncate(fb.agentAnswer)}</td>
                <td class="vote">
                  {#if fb.feedback === 'UP'}
                    <span class="badge up">
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/></svg>
                    </span>
                  {:else}
                    <span class="badge down">
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" style="transform:rotate(180deg)"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/></svg>
                    </span>
                  {/if}
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </div>
  </div>
</div>

<style>
  .overlay {
    position: fixed;
    inset: 0;
    background: rgba(0,0,0,0.35);
    backdrop-filter: blur(4px);
    z-index: 400;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .panel {
    background: var(--glass-bg);
    backdrop-filter: blur(var(--glass-blur));
    border: 1px solid var(--glass-border);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-lg), var(--shadow-glow);
    width: min(900px, 94vw);
    max-height: 80vh;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .panel-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 18px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
  }

  .panel-title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .close-btn {
    width: 28px;
    height: 28px;
    border: none;
    background: none;
    border-radius: var(--radius-sm);
    cursor: pointer;
    color: var(--color-text-muted);
    display: flex;
    align-items: center;
    justify-content: center;
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease, transform var(--duration-fast) var(--ease-spring);
  }

  .close-btn:hover {
    background: var(--color-surface-hover);
    color: var(--color-text-primary);
    transform: scale(1.1) rotate(90deg);
  }

  .panel-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 18px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
    flex-wrap: wrap;
  }

  .stats {
    display: flex;
    gap: 6px;
  }

  .stat-chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 3px 8px;
    border-radius: 20px;
    font-size: 11.5px;
    font-weight: 600;
  }

  .stat-chip.up   { background: rgba(45, 212, 191, 0.12); color: var(--color-success); }
  .stat-chip.down { background: rgba(248, 113, 113, 0.12); color: var(--color-danger); }

  .filter-tabs {
    display: flex;
    gap: 2px;
    margin-left: auto;
  }

  .tab {
    padding: 4px 10px;
    border: 1px solid var(--color-border);
    background: none;
    border-radius: var(--radius-sm);
    font-size: 11.5px;
    cursor: pointer;
    color: var(--color-text-muted);
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease, transform var(--duration-fast) var(--ease-spring);
  }

  .tab:hover { transform: translateY(-1px); }

  .tab.active {
    background: var(--gradient-accent);
    border-color: var(--color-accent);
    color: #081019;
  }

  .reload-btn {
    width: 28px;
    height: 28px;
    border: 1px solid var(--color-border);
    background: none;
    border-radius: var(--radius-sm);
    cursor: pointer;
    color: var(--color-text-muted);
    display: flex;
    align-items: center;
    justify-content: center;
    transition: background var(--duration-fast) ease, transform var(--duration-fast) var(--ease-spring);
  }

  .reload-btn:hover {
    background: var(--color-surface-hover);
    color: var(--color-text-primary);
    transform: scale(1.1) rotate(120deg);
  }

  .panel-body {
    flex: 1;
    overflow-y: auto;
  }

  .centered {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 200px;
    font-size: 13px;
  }

  .muted  { color: var(--color-text-muted); }
  .error  { color: var(--color-danger); }

  .spinner {
    width: 20px;
    height: 20px;
    border: 2px solid var(--color-border);
    border-top-color: var(--color-accent);
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
  }

  @keyframes spin { to { transform: rotate(360deg); } }

  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12.5px;
  }

  thead th {
    position: sticky;
    top: 0;
    background: var(--color-surface);
    padding: 9px 14px;
    text-align: left;
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--color-text-muted);
    border-bottom: 1px solid var(--color-border);
  }

  tbody tr {
    border-bottom: 1px solid var(--color-border);
    transition: background 0.1s;
  }

  tbody tr:hover { background: var(--color-surface-hover); }
  tbody tr:last-child { border-bottom: none; }

  tbody td {
    padding: 9px 14px;
    color: var(--color-text-primary);
    vertical-align: top;
  }

  td.date  { color: var(--color-text-muted); font-size: 11.5px; white-space: nowrap; }
  td.user  { font-weight: 500; white-space: nowrap; max-width: 100px; }
  td.prompt { max-width: 260px; max-height: 80px; overflow-y: auto; word-break: break-word; color: var(--color-text-secondary); display: block; }
  td.answer { max-width: 260px; word-break: break-word; color: var(--color-text-secondary); }
  td.vote  { width: 40px; text-align: center; }

  .badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    border-radius: 50%;
  }

  .badge.up   { background: rgba(45, 212, 191, 0.12); color: var(--color-success); }
  .badge.down { background: rgba(248, 113, 113, 0.12); color: var(--color-danger); }
</style>
