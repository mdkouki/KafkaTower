<script>
  import { createEventDispatcher, onMount } from 'svelte';
  import { loadSettings, saveSettings } from './settingsApi.js';

  export let busy = false;
  const dispatch = createEventDispatcher();

  const URL = '/api/admin/settings/opensearch';
  let enabled = false;
  let defaultCluster = '';
  // Each row: name, url, username, index, password (new value), passwordSet, clearPassword
  let clusters = [];

  let loading = true;
  let saving = false;
  let error = null;
  let successNote = null;

  onMount(async () => {
    try {
      const data = await loadSettings(URL);
      enabled = data.enabled;
      defaultCluster = data.defaultCluster;
      clusters = data.clusters.map(c => ({ ...c, previousName: c.name, password: '', clearPassword: false }));
    } catch (e) {
      error = e.message;
    } finally {
      loading = false;
    }
  });

  function addCluster() {
    clusters = [...clusters, { name: '', previousName: '', url: '', username: '', index: '', password: '', passwordSet: false, clearPassword: false }];
  }

  function removeCluster(i) {
    const removed = clusters[i].name;
    clusters = clusters.filter((_, j) => j !== i);
    if (removed && removed === defaultCluster) defaultCluster = '';
  }

  async function handleSave() {
    saving = true;
    error = null;
    successNote = null;
    try {
      const data = await saveSettings(URL, {
        enabled,
        defaultCluster: defaultCluster.trim(),
        clusters: clusters.map(c => ({
          name: c.name.trim(), previousName: c.previousName, url: c.url.trim(), username: c.username.trim(), index: c.index.trim(),
          password: c.password.trim(), clearPassword: c.clearPassword,
        })),
      });
      successNote = data.note;
      clusters = clusters.map(c => ({
        ...c,
        previousName: c.name.trim(),
        passwordSet: c.clearPassword ? false : c.passwordSet || !!c.password.trim(),
        password: '', clearPassword: false,
      }));
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
    <p class="section-hint">One OpenSearch connection per Kafka cluster, used by the log inspector. The cluster name must match the Kafka cluster name. Saving first checks every connection (and its index), then restarts the application context.</p>

    <label class="enable-row">
      <input type="checkbox" bind:checked={enabled} />
      <span>Enable log inspection</span>
    </label>

    {#each clusters as c, i}
      <fieldset class="cluster">
        <legend>{c.name || 'New cluster'}</legend>
        <div class="field">
          <label for="os-name-{i}">Kafka cluster name</label>
          <input id="os-name-{i}" type="text" bind:value={c.name} />
        </div>
        <div class="field">
          <label for="os-url-{i}">URL</label>
          <input id="os-url-{i}" type="text" placeholder="https://opensearch:9200" bind:value={c.url} />
        </div>
        <div class="field">
          <label for="os-index-{i}">Index / pattern</label>
          <input id="os-index-{i}" type="text" placeholder="app-logs-*" bind:value={c.index} />
        </div>
        <div class="field">
          <label for="os-user-{i}">User (optional)</label>
          <input id="os-user-{i}" type="text" autocomplete="off" bind:value={c.username} />
        </div>
        <div class="field">
          <label for="os-pass-{i}">Password</label>
          {#if c.passwordSet && !c.clearPassword}
            <p class="current-value">A password is set. <button class="text-btn" on:click={() => (c.clearPassword = true)}>Remove</button></p>
          {:else if c.clearPassword}
            <p class="current-value">The password will be removed. <button class="text-btn" on:click={() => (c.clearPassword = false)}>Undo</button></p>
          {/if}
          <input id="os-pass-{i}" type="password" autocomplete="new-password"
            placeholder={c.passwordSet ? 'Enter new password to replace…' : 'Password…'} bind:value={c.password} />
        </div>
        <button class="text-btn danger" on:click={() => removeCluster(i)}>Remove cluster</button>
      </fieldset>
    {/each}

    <button class="btn-secondary" on:click={addCluster}>+ Add cluster</button>

    {#if clusters.length}
      <div class="field">
        <label for="os-default">Default cluster</label>
        <select id="os-default" bind:value={defaultCluster}>
          <option value="">None</option>
          {#each clusters.filter(c => c.name.trim()) as c}<option value={c.name.trim()}>{c.name.trim()}</option>{/each}
        </select>
      </div>
    {/if}

    {#if error}<p class="msg error">{error}</p>{/if}
    {#if successNote}<p class="msg success">{successNote}</p>{/if}

    <div class="actions">
      <button class="btn-save" on:click={handleSave} disabled={saving || busy}>
        {saving ? 'Verifying…' : 'Verify & save'}
      </button>
    </div>
  </div>
{/if}
