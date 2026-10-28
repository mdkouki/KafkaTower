<script>
  import { createEventDispatcher, onMount } from 'svelte';
  import { loadSettings, saveSettings } from './settingsApi.js';

  export let busy = false;
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
      const data = await loadSettings('/api/admin/llm-settings');
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
      const data = await saveSettings('/api/admin/llm-settings', body);
      successNote = data.note;
      if (body.apiKey) { apiKey = ''; apiKeyMasked = '****' + body.apiKey.slice(-4); }
      if (data.restarting) dispatch('restart');
    } catch (e) {
      error = e.message;
    } finally {
      saving = false;
    }
  }
</script>

{#if loading}
  <div class="loading-state"><div class="spinner"></div></div>
{:else}
  <div class="settings-form">
    <p class="section-hint">The LLM endpoint used by the AI agents. Saving first checks that the endpoint accepts the key and serves the model, then restarts the application context (a few seconds).</p>

    <div class="field">
      <label for="llm-api-key">API Key</label>
      {#if apiKeySet}<p class="current-value">Current: <code>{apiKeyMasked}</code></p>{/if}
      <input id="llm-api-key" type="password" autocomplete="new-password"
        placeholder={apiKeySet ? 'Enter new key to replace…' : 'Enter API key…'} bind:value={apiKey} />
    </div>
    <div class="field">
      <label for="llm-base-url">Base URL</label>
      <input id="llm-base-url" type="text" placeholder="https://api.example.com/v1" bind:value={baseUrl} />
    </div>
    <div class="field">
      <label for="llm-model">Model</label>
      <input id="llm-model" type="text" placeholder="mistral-medium-2508" bind:value={model} />
    </div>

    {#if error}<p class="msg error">{error}</p>{/if}
    {#if successNote}<p class="msg success">{successNote}</p>{/if}

    <div class="actions">
      <button class="btn-save" on:click={handleSave} disabled={saving || busy}>
        {saving ? 'Verifying…' : 'Verify & save'}
      </button>
    </div>
  </div>
{/if}
