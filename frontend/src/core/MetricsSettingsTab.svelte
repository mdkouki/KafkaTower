<script>
  import { createEventDispatcher, onMount } from 'svelte';
  import { loadSettings, saveSettings } from './settingsApi.js';

  export let busy = false;
  const dispatch = createEventDispatcher();

  const URL = '/api/admin/settings/metrics';
  let url = '';
  let username = '';
  let tenant = '';
  let token = '';
  let password = '';
  let tokenSet = false;
  let passwordSet = false;
  let clearToken = false;
  let clearPassword = false;

  let loading = true;
  let saving = false;
  let error = null;
  let successNote = null;

  onMount(async () => {
    try {
      const data = await loadSettings(URL);
      url = data.url;
      username = data.username;
      tenant = data.tenant;
      tokenSet = data.tokenSet;
      passwordSet = data.passwordSet;
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
      const data = await saveSettings(URL, {
        url: url.trim(), username: username.trim(), tenant: tenant.trim(),
        token: token.trim(), clearToken, password: password.trim(), clearPassword,
      });
      successNote = data.note;
      tokenSet = clearToken ? false : tokenSet || !!token.trim();
      passwordSet = clearPassword ? false : passwordSet || !!password.trim();
      token = ''; password = ''; clearToken = false; clearPassword = false;
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
    <p class="section-hint">The Prometheus-compatible store behind the metrics tools (VictoriaMetrics, Prometheus, Thanos, Mimir…). Saving first queries the backend with these parameters, then restarts the application context.</p>

    <div class="field">
      <label for="m-url">URL</label>
      <input id="m-url" type="text" placeholder="http://localhost:8428" bind:value={url} />
    </div>
    <div class="field">
      <label for="m-token">Bearer token (optional)</label>
      {#if tokenSet && !clearToken}
        <p class="current-value">A token is set. <button class="text-btn" on:click={() => (clearToken = true)}>Remove</button></p>
      {:else if clearToken}
        <p class="current-value">The token will be removed. <button class="text-btn" on:click={() => (clearToken = false)}>Undo</button></p>
      {/if}
      <input id="m-token" type="password" autocomplete="new-password"
        placeholder={tokenSet ? 'Enter new token to replace…' : 'Token…'} bind:value={token} />
    </div>
    <div class="field">
      <label for="m-user">Basic auth user (optional)</label>
      <input id="m-user" type="text" autocomplete="off" bind:value={username} />
    </div>
    <div class="field">
      <label for="m-pass">Basic auth password</label>
      {#if passwordSet && !clearPassword}
        <p class="current-value">A password is set. <button class="text-btn" on:click={() => (clearPassword = true)}>Remove</button></p>
      {:else if clearPassword}
        <p class="current-value">The password will be removed. <button class="text-btn" on:click={() => (clearPassword = false)}>Undo</button></p>
      {/if}
      <input id="m-pass" type="password" autocomplete="new-password"
        placeholder={passwordSet ? 'Enter new password to replace…' : 'Password…'} bind:value={password} />
    </div>
    <div class="field">
      <label for="m-tenant">Tenant / X-Scope-OrgID (optional)</label>
      <input id="m-tenant" type="text" autocomplete="off" bind:value={tenant} />
    </div>

    {#if error}<p class="msg error">{error}</p>{/if}
    {#if successNote}<p class="msg success">{successNote}</p>{/if}

    <div class="actions">
      <button class="btn-save" on:click={handleSave} disabled={saving || busy || !url.trim()}>
        {saving ? 'Verifying…' : 'Verify & save'}
      </button>
    </div>
  </div>
{/if}
