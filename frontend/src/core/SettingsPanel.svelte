<script>
  import { createEventDispatcher } from 'svelte';
  import { fade, fly } from 'svelte/transition';
  import { motionParams } from './motion.js';
  import LlmSettingsTab from './LlmSettingsTab.svelte';
  import KafkaSettingsTab from './KafkaSettingsTab.svelte';
  import OpenSearchSettingsTab from './OpenSearchSettingsTab.svelte';
  import MetricsSettingsTab from './MetricsSettingsTab.svelte';

  const dispatch = createEventDispatcher();

  const tabs = [
    { id: 'llm', label: 'LLM' },
    { id: 'kafka', label: 'Kafka' },
    { id: 'opensearch', label: 'OpenSearch' },
    { id: 'metrics', label: 'Metrics' },
  ];
  let active = 'llm';
  let restarting = false;
  let error = null;

  // The backend restarts its Spring context to apply the settings. The public start page is
  // unreachable while it is down; once it answers again, reload (sessions do not survive a restart).
  async function waitForRestart() {
    restarting = true;
    let wentDown = false;
    const startedAt = Date.now();
    while (Date.now() - startedAt < 120000) {
      await new Promise(r => setTimeout(r, 1500));
      try {
        const res = await fetch('/', { cache: 'no-store' });
        if (res.ok && (wentDown || Date.now() - startedAt > 8000)) {
          window.location.reload();
          return;
        }
      } catch {
        wentDown = true;
      }
    }
    restarting = false;
    error = 'The application did not come back within 2 minutes. Check the server logs.';
  }
</script>

<div
  class="panel-overlay"
  on:click|self={() => dispatch('close')}
  role="presentation"
  transition:fade={motionParams({ duration: 180 })}
>
  <div class="panel" transition:fly={motionParams({ x: 40, duration: 240 })}>
    <div class="panel-header">
      <h2>Settings</h2>
      <button class="close-btn" on:click={() => dispatch('close')} aria-label="Close">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      </button>
    </div>

    <div class="tabs" role="tablist">
      {#each tabs as t}
        <button
          class="tab"
          class:active={active === t.id}
          role="tab"
          aria-selected={active === t.id}
          on:click={() => (active = t.id)}
        >{t.label}</button>
      {/each}
    </div>

    <div class="panel-body" role="tabpanel">
      {#if restarting}
        <p class="msg success">Restarting the application… this page reloads when it is back.</p>
      {/if}
      {#if error}<p class="msg error">{error}</p>{/if}
      {#if active === 'llm'}
        <LlmSettingsTab busy={restarting} on:restart={waitForRestart} />
      {:else if active === 'kafka'}
        <KafkaSettingsTab busy={restarting} on:restart={waitForRestart} />
      {:else if active === 'opensearch'}
        <OpenSearchSettingsTab busy={restarting} on:restart={waitForRestart} />
      {:else}
        <MetricsSettingsTab busy={restarting} on:restart={waitForRestart} />
      {/if}
    </div>
  </div>
</div>

<style>
  .panel-overlay {
    position: fixed;
    inset: 0;
    background: rgba(0,0,0,0.45);
    backdrop-filter: blur(4px);
    z-index: 200;
    display: flex;
    align-items: flex-start;
    justify-content: flex-end;
  }
  .panel {
    background: var(--glass-bg);
    backdrop-filter: blur(var(--glass-blur));
    border-left: 1px solid var(--glass-border);
    width: 440px;
    max-width: 100vw;
    height: 100vh;
    display: flex;
    flex-direction: column;
    box-shadow: var(--shadow-lg);
  }
  .panel-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 20px;
    border-bottom: 1px solid var(--color-border);
  }
  .panel-header h2 {
    font-size: 15px;
    font-weight: 600;
    color: var(--color-text-primary);
    margin: 0;
  }
  .close-btn {
    background: none;
    border: none;
    cursor: pointer;
    color: var(--color-text-muted);
    padding: 4px;
    display: flex;
    border-radius: var(--radius-sm);
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease, transform var(--duration-fast) var(--ease-spring);
  }
  .close-btn:hover { background: var(--color-surface-hover); color: var(--color-text-primary); transform: scale(1.08); }
  .close-btn:active { transform: scale(0.92); }

  .panel-body {
    padding: 20px;
    overflow-y: auto;
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .tabs { display: flex; gap: 4px; padding: 0 20px; border-bottom: 1px solid var(--color-border); }
  .tab {
    background: none; border: none; border-bottom: 2px solid transparent; cursor: pointer;
    padding: 10px 12px; font-size: 13px; font-weight: 500; color: var(--color-text-muted);
    transition: color var(--duration-fast) ease, border-color var(--duration-fast) ease;
  }
  .tab:hover { color: var(--color-text-primary); }
  .tab.active { color: var(--color-accent); border-bottom-color: var(--color-accent); }

  .panel-body :global(.loading-state) { display: flex; align-items: center; justify-content: center; }
  .panel-body :global(.settings-form) { display: flex; flex-direction: column; gap: 16px; padding-bottom: 72px; /* clear of the floating chat button */ }
  .panel-body :global(.section-hint) {
    font-size: 12px;
    color: var(--color-text-muted);
    margin: 0;
    line-height: 1.5;
    background: var(--color-surface-hover);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    padding: 10px 12px;
  }

  .panel-body :global(.field) {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .panel-body :global(.field label) {
    font-size: 12px;
    font-weight: 500;
    color: var(--color-text-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .panel-body :global(.current-value) {
    font-size: 11px;
    color: var(--color-text-muted);
    margin: 0;
  }
  .panel-body :global(.current-value code) {
    font-family: monospace;
    color: var(--color-text-primary);
  }
  .panel-body :global(.field input) {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    padding: 8px 10px;
    font-size: 13px;
    color: var(--color-text-primary);
    width: 100%;
    box-sizing: border-box;
    outline: none;
    transition: border-color var(--duration-base) ease, box-shadow var(--duration-base) ease;
  }
  .panel-body :global(.field input:focus) { border-color: var(--color-accent); box-shadow: 0 0 0 3px var(--color-accent-light); }

  .panel-body :global(.msg) {
    font-size: 12px;
    padding: 8px 12px;
    border-radius: 6px;
    margin: 0;
  }
  .panel-body :global(.msg.error) { background: rgba(248, 113, 113, 0.1); color: var(--color-danger); border: 1px solid rgba(248, 113, 113, 0.3); }
  .panel-body :global(.msg.success) { background: rgba(45, 212, 191, 0.1); color: var(--color-success); border: 1px solid rgba(45, 212, 191, 0.3); }

  .panel-body :global(.actions) { display: flex; justify-content: flex-end; }
  .panel-body :global(.btn-save) {
    background: var(--gradient-accent);
    color: #081019;
    border: none;
    border-radius: var(--radius-md);
    padding: 8px 20px;
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    transition: transform var(--duration-fast) var(--ease-spring), box-shadow var(--duration-base) ease, opacity var(--duration-base) ease;
  }
  .panel-body :global(.btn-save:hover:not(:disabled)) { box-shadow: var(--shadow-glow); transform: translateY(-1px); }
  .panel-body :global(.btn-save:active:not(:disabled)) { transform: translateY(0) scale(0.97); }
  .panel-body :global(.btn-save:disabled) { opacity: 0.5; cursor: not-allowed; }

  .panel-body :global(.spinner) {
    width: 24px; height: 24px;
    border: 2px solid var(--color-border);
    border-top-color: var(--color-accent);
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
  }
  @media (prefers-reduced-motion: reduce) {
    .panel-body :global(.spinner) { animation-duration: 2s; }
  }
  .panel-body :global(.field select) {
    background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md);
    padding: 8px 10px; font-size: 13px; color: var(--color-text-primary); width: 100%; box-sizing: border-box;
  }
  .panel-body :global(.enable-row input[type="checkbox"]) {
    width: 16px; height: 16px; padding: 0; margin: 0; flex: none; accent-color: var(--color-accent);
    appearance: auto; -webkit-appearance: checkbox;
  }
  .panel-body :global(.enable-row) { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--color-text-primary); }
  .panel-body :global(.cluster) {
    border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 12px;
    display: flex; flex-direction: column; gap: 12px; margin: 0;
  }
  .panel-body :global(.cluster legend) { font-size: 12px; font-weight: 600; color: var(--color-text-primary); padding: 0 6px; }
  .panel-body :global(.text-btn) {
    background: none; border: none; padding: 0; cursor: pointer; font-size: 11px; color: var(--color-accent);
    text-decoration: underline; align-self: flex-start;
  }
  .panel-body :global(.text-btn.danger) { color: var(--color-danger); }
  .panel-body :global(.btn-secondary) {
    background: var(--color-surface-hover); color: var(--color-text-primary); border: 1px solid var(--color-border);
    border-radius: var(--radius-md); padding: 7px 14px; font-size: 12px; cursor: pointer; align-self: flex-start;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
</style>