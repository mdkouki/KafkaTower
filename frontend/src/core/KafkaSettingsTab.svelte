<script>
  import { createEventDispatcher, onMount } from 'svelte';
  import { loadSettings, saveSettings } from './settingsApi.js';

  export let busy = false;
  const dispatch = createEventDispatcher();

  const URL = '/api/admin/settings/kafka';
  const STORE_TYPES = ['PKCS12', 'JKS'];
  const MECHANISMS = ['PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512'];

  // Each row mirrors the backend view, plus the secrets typed in this session and the name the row was loaded with.
  let clusters = [];
  let removed = [];

  let loading = true;
  let saving = false;
  let error = null;
  let successNote = null;

  function toRow(c) {
    return {
      previousName: c.name, name: c.name, bootstrapServers: c.bootstrapServers, security: c.security,
      ssl: { ...c.ssl, keystorePassword: '', keyPassword: '', truststorePassword: '' },
      sasl: { ...c.sasl, password: '' },
    };
  }

  function emptyRow() {
    return {
      previousName: '', name: '', bootstrapServers: '', security: 'NONE',
      ssl: { keystoreLocation: '', keystoreType: 'PKCS12', keystorePasswordSet: false, keystorePassword: '',
             keyPasswordSet: false, keyPassword: '', truststoreLocation: '', truststoreType: 'PKCS12',
             truststorePasswordSet: false, truststorePassword: '' },
      sasl: { mechanism: 'PLAIN', username: '', passwordSet: false, password: '', tls: false },
    };
  }

  onMount(async () => {
    try {
      const data = await loadSettings(URL);
      clusters = data.clusters.map(toRow);
    } catch (e) {
      error = e.message;
    } finally {
      loading = false;
    }
  });

  function addCluster() {
    clusters = [...clusters, emptyRow()];
  }

  function removeCluster(i) {
    const c = clusters[i];
    if (c.previousName) {
      if (!confirm(`Remove cluster '${c.previousName}' and delete its stored data (uploaded topic schemas)? This happens when you save.`)) return;
      removed = [...removed, c.previousName];
    }
    clusters = clusters.filter((_, j) => j !== i);
  }

  async function handleSave() {
    saving = true;
    error = null;
    successNote = null;
    try {
      const data = await saveSettings(URL, {
        removedClusters: removed,
        clusters: clusters.map(c => ({
          name: c.name.trim(), previousName: c.previousName, bootstrapServers: c.bootstrapServers.trim(),
          security: c.security,
          ssl: {
            keystoreLocation: c.ssl.keystoreLocation.trim(), keystoreType: c.ssl.keystoreType,
            keystorePassword: c.ssl.keystorePassword, keyPassword: c.ssl.keyPassword,
            truststoreLocation: c.ssl.truststoreLocation.trim(), truststoreType: c.ssl.truststoreType,
            truststorePassword: c.ssl.truststorePassword,
          },
          sasl: { mechanism: c.sasl.mechanism, username: c.sasl.username.trim(), password: c.sasl.password, tls: c.sasl.tls },
        })),
      });
      successNote = data.note;
      removed = [];
      clusters = clusters.map(c => ({
        ...c,
        previousName: c.name.trim(),
        ssl: {
          ...c.ssl,
          keystorePasswordSet: c.ssl.keystorePasswordSet || !!c.ssl.keystorePassword, keystorePassword: '',
          keyPasswordSet: c.ssl.keyPasswordSet || !!c.ssl.keyPassword, keyPassword: '',
          truststorePasswordSet: c.ssl.truststorePasswordSet || !!c.ssl.truststorePassword, truststorePassword: '',
        },
        sasl: { ...c.sasl, passwordSet: c.sasl.passwordSet || !!c.sasl.password, password: '' },
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
    <p class="section-hint">The Kafka clusters this console inspects. Saving first connects to every cluster with these settings, then restarts the application context. Certificates are read from files on the server, so give their paths there. Removing a cluster deletes its stored data; renaming one keeps the old name's data.</p>

    {#if clusters.length === 0}
      <p class="current-value">No cluster configured yet.</p>
    {/if}

    {#each clusters as c, i}
      <fieldset class="cluster">
        <legend>{c.name || 'New cluster'}</legend>
        <div class="field">
          <label for="k-name-{i}">Cluster name</label>
          <input id="k-name-{i}" type="text" bind:value={c.name} />
        </div>
        <div class="field">
          <label for="k-boot-{i}">Bootstrap servers</label>
          <input id="k-boot-{i}" type="text" placeholder="broker1:9092,broker2:9092" bind:value={c.bootstrapServers} />
        </div>
        <div class="field">
          <label for="k-sec-{i}">Security</label>
          <select id="k-sec-{i}" bind:value={c.security}>
            <option value="NONE">None (plaintext)</option>
            <option value="MTLS">mTLS (client certificate)</option>
            <option value="SASL">SASL (username / password)</option>
          </select>
        </div>

        {#if c.security === 'SASL'}
          <div class="field">
            <label for="k-mech-{i}">SASL mechanism</label>
            <select id="k-mech-{i}" bind:value={c.sasl.mechanism}>
              {#each MECHANISMS as m}<option value={m}>{m}</option>{/each}
            </select>
          </div>
          <div class="field">
            <label for="k-user-{i}">Username</label>
            <input id="k-user-{i}" type="text" autocomplete="off" bind:value={c.sasl.username} />
          </div>
          <div class="field">
            <label for="k-pass-{i}">Password</label>
            {#if c.sasl.passwordSet}<p class="current-value">A password is set. Type to replace it.</p>{/if}
            <input id="k-pass-{i}" type="password" autocomplete="new-password"
              placeholder={c.sasl.passwordSet ? 'Enter new password to replace…' : 'Password…'} bind:value={c.sasl.password} />
          </div>
          <label class="enable-row">
            <input type="checkbox" bind:checked={c.sasl.tls} />
            <span>Encrypt with TLS (SASL_SSL)</span>
          </label>
        {/if}

        {#if c.security === 'MTLS'}
          <div class="field">
            <label for="k-ks-{i}">Keystore path (client certificate)</label>
            <input id="k-ks-{i}" type="text" placeholder="/etc/kafka/certs/client.p12" bind:value={c.ssl.keystoreLocation} />
          </div>
          <div class="field">
            <label for="k-kst-{i}">Keystore type</label>
            <select id="k-kst-{i}" bind:value={c.ssl.keystoreType}>
              {#each STORE_TYPES as t}<option value={t}>{t}</option>{/each}
            </select>
          </div>
          <div class="field">
            <label for="k-ksp-{i}">Keystore password</label>
            {#if c.ssl.keystorePasswordSet}<p class="current-value">A password is set. Type to replace it.</p>{/if}
            <input id="k-ksp-{i}" type="password" autocomplete="new-password" bind:value={c.ssl.keystorePassword} />
          </div>
          <div class="field">
            <label for="k-kp-{i}">Key password (if different)</label>
            {#if c.ssl.keyPasswordSet}<p class="current-value">A password is set. Type to replace it.</p>{/if}
            <input id="k-kp-{i}" type="password" autocomplete="new-password" bind:value={c.ssl.keyPassword} />
          </div>
        {/if}

        {#if c.security === 'MTLS' || (c.security === 'SASL' && c.sasl.tls)}
          <div class="field">
            <label for="k-ts-{i}">Truststore path (optional)</label>
            <input id="k-ts-{i}" type="text" placeholder="/etc/kafka/certs/truststore.p12" bind:value={c.ssl.truststoreLocation} />
          </div>
          <div class="field">
            <label for="k-tst-{i}">Truststore type</label>
            <select id="k-tst-{i}" bind:value={c.ssl.truststoreType}>
              {#each STORE_TYPES as t}<option value={t}>{t}</option>{/each}
            </select>
          </div>
          <div class="field">
            <label for="k-tsp-{i}">Truststore password</label>
            {#if c.ssl.truststorePasswordSet}<p class="current-value">A password is set. Type to replace it.</p>{/if}
            <input id="k-tsp-{i}" type="password" autocomplete="new-password" bind:value={c.ssl.truststorePassword} />
          </div>
        {/if}

        <button class="text-btn danger" on:click={() => removeCluster(i)}>Remove cluster</button>
      </fieldset>
    {/each}

    <button class="btn-secondary" on:click={addCluster}>+ Add cluster</button>

    {#if error}<p class="msg error">{error}</p>{/if}
    {#if successNote}<p class="msg success">{successNote}</p>{/if}

    <div class="actions">
      <button class="btn-save" on:click={handleSave} disabled={saving || busy}>
        {saving ? 'Verifying…' : 'Verify & save'}
      </button>
    </div>
  </div>
{/if}
