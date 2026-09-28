<script>
  import { createEventDispatcher, onMount } from 'svelte';
  import { fade, fly } from 'svelte/transition';
  import { motionParams } from './motion.js';

  const dispatch = createEventDispatcher();

  let apiKey = '';
  let baseUrl = '';
  let model = '';
  let apiKeyMasked = '';

  $: apiKeySet = !!apiKeyMasked;

  let loading = true;
  let saving = false;
  let error = null;
  let successNote = null;

  onMount(async () => {
    try {
      const res = await fetch('/api/admin/llm-settings');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      apiKeyMasked = data.apiKeyMasked;
      baseUrl = data.baseUrl;
      model = data.model;
    } catch (e) {
      error = e.message;
    } finally {
      loading = false;
    }
  });

  async function handleSave() {
    saving = true;
    error = null;
    successNote = null;
    try {
      const body = { baseUrl: baseUrl.trim(), model: model.trim() };
      if (apiKey.trim()) body.apiKey = apiKey.trim();
      const res = await fetch('/api/admin/llm-settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      successNote = data.note;
      if (body.apiKey) { apiKey = ''; apiKeyMasked = '****' + body.apiKey.slice(-4); }
    } catch (e) {
      error = e.message;
    } finally {
      saving = false;
    }
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
      <h2>LLM Settings</h2>
      <button class="close-btn" on:click={() => dispatch('close')} aria-label="Close">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      </button>
    </div>

    {#if loading}
      <div class="panel-body loading-state">
        <div class="spinner"></div>
      </div>
    {:else}
      <div class="panel-body">
        <p class="section-hint">Configure the LLM endpoint used by the AI agents. Changes take effect after restarting the application.</p>

        <div class="field">
          <label for="llm-api-key">API Key</label>
          {#if apiKeySet}
            <p class="current-value">Current: <code>{apiKeyMasked}</code></p>
          {/if}
          <input
            id="llm-api-key"
            type="password"
            placeholder={apiKeySet ? 'Enter new key to replace…' : 'Enter API key…'}
            bind:value={apiKey}
            autocomplete="new-password"
          />
        </div>

        <div class="field">
          <label for="llm-base-url">Base URL</label>
          <input
            id="llm-base-url"
            type="text"
            placeholder="https://api.example.com/v1"
            bind:value={baseUrl}
          />
        </div>

        <div class="field">
          <label for="llm-model">Model</label>
          <input
            id="llm-model"
            type="text"
            placeholder="gpt-4o"
            bind:value={model}
          />
        </div>

        {#if error}
          <p class="msg error">{error}</p>
        {/if}
        {#if successNote}
          <p class="msg success">{successNote}</p>
        {/if}

        <div class="actions">
          <button class="btn-save" on:click={handleSave} disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    {/if}
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
    width: 400px;
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
  .loading-state { align-items: center; justify-content: center; }

  .section-hint {
    font-size: 12px;
    color: var(--color-text-muted);
    margin: 0;
    line-height: 1.5;
    background: var(--color-surface-hover);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    padding: 10px 12px;
  }

  .field {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .field label {
    font-size: 12px;
    font-weight: 500;
    color: var(--color-text-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .current-value {
    font-size: 11px;
    color: var(--color-text-muted);
    margin: 0;
  }
  .current-value code {
    font-family: monospace;
    color: var(--color-text-primary);
  }
  .field input {
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
  .field input:focus { border-color: var(--color-accent); box-shadow: 0 0 0 3px var(--color-accent-light); }

  .msg {
    font-size: 12px;
    padding: 8px 12px;
    border-radius: 6px;
    margin: 0;
  }
  .msg.error { background: rgba(248, 113, 113, 0.1); color: var(--color-danger); border: 1px solid rgba(248, 113, 113, 0.3); }
  .msg.success { background: rgba(45, 212, 191, 0.1); color: var(--color-success); border: 1px solid rgba(45, 212, 191, 0.3); }

  .actions { display: flex; justify-content: flex-end; }
  .btn-save {
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
  .btn-save:hover:not(:disabled) { box-shadow: var(--shadow-glow); transform: translateY(-1px); }
  .btn-save:active:not(:disabled) { transform: translateY(0) scale(0.97); }
  .btn-save:disabled { opacity: 0.5; cursor: not-allowed; }

  .spinner {
    width: 24px; height: 24px;
    border: 2px solid var(--color-border);
    border-top-color: var(--color-accent);
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
  }
  @media (prefers-reduced-motion: reduce) {
    .spinner { animation-duration: 2s; }
  }
  @keyframes spin { to { transform: rotate(360deg); } }
</style>