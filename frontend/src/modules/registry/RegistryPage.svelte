<script>
  import { onMount } from 'svelte';
  import { authUser } from '../../core/auth.js';
  import GroupStatePopup from './GroupStatePopup.svelte';
  import HudCore from './HudCore.svelte';

  let clusters = [];
  let selectedClusterName = null;
  let loadingClusters = true;
  let clustersError = null;

  let users = [];
  let loadingUsers = false;
  let usersError = null;
  let status = null;

  let searchQuery = '';
  let selectedPrincipal = null;
  let detail = null;
  let loadingDetail = false;
  let detailError = null;

  let openGroupPopup = null; // { group } | null

  onMount(loadClusters);

  async function loadClusters() {
    loadingClusters = true;
    clustersError = null;
    try {
      const res = await fetch('/api/registry/clusters');
      if (res.status === 401 || res.status === 403) {
        authUser.set(false);
        return;
      }
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      clusters = await res.json();
      if (clusters.length > 0) {
        selectedClusterName = clusters[0];
        await loadUsers();
      }
    } catch (e) {
      clustersError = e.message;
    } finally {
      loadingClusters = false;
    }
  }

  async function handleClusterChange(event) {
    selectedClusterName = event.target.value;
    selectedPrincipal = null;
    detail = null;
    await loadUsers();
  }

  async function loadUsers() {
    if (!selectedClusterName) return;
    loadingUsers = true;
    usersError = null;
    users = [];
    status = null;
    selectedPrincipal = null;
    detail = null;
    try {
      const [usersRes, statusRes] = await Promise.all([
        fetch(`/api/registry/acl-users?clusterName=${encodeURIComponent(selectedClusterName)}`),
        fetch(`/api/registry/acl-users/status?clusterName=${encodeURIComponent(selectedClusterName)}`)
      ]);
      if (usersRes.status === 401 || usersRes.status === 403) {
        authUser.set(false);
        return;
      }
      if (usersRes.status === 404) {
        // ACL graph hasn't been built yet for this cluster (refresh job hasn't run) — not an error.
        users = [];
        return;
      }
      if (!usersRes.ok) throw new Error(`HTTP ${usersRes.status}`);
      users = await usersRes.json();
      status = statusRes.ok ? await statusRes.json() : null;
      if (users.length > 0) {
        await loadDetail(users[0].principal);
      }
    } catch (e) {
      usersError = e.message;
    } finally {
      loadingUsers = false;
    }
  }

  async function loadDetail(principal) {
    selectedPrincipal = principal;
    detail = null;
    detailError = null;
    loadingDetail = true;
    try {
      const res = await fetch(`/api/registry/acl-users/detail?clusterName=${encodeURIComponent(selectedClusterName)}&principal=${encodeURIComponent(principal)}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      detail = await res.json();
    } catch (e) {
      detailError = e.message;
    } finally {
      loadingDetail = false;
    }
  }

  $: filteredUsers = searchQuery
    ? users.filter(u => u.principal.toLowerCase().includes(searchQuery.toLowerCase()))
    : users;

  const dateTimeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

  function formatBuiltAt(iso) {
    if (!iso) return null;
    try {
      return dateTimeFormat.format(new Date(iso));
    } catch (_) {
      return iso;
    }
  }

  function openGroup(group) {
    openGroupPopup = { group };
  }

  function handleViewGroup(event) {
    openGroup(event.detail);
  }

  let announcement = '';
  function handleCopied(event) {
    announcement = `Copied ${event.detail} to clipboard`;
  }
</script>

<div class="registry-page">
  <div class="toolbar" role="region" aria-label="Cluster selection and sync status">
    <div class="toolbar-cluster">
      {#if clusters.length > 0}
        <span class="toolbar-label">Cluster</span>
        <select class="cluster-select" aria-label="Kafka cluster" value={selectedClusterName} on:change={handleClusterChange}>
          {#each clusters as cluster}
            <option value={cluster}>{cluster}</option>
          {/each}
        </select>
      {:else if !loadingClusters}
        <span class="toolbar-muted">No Kafka clusters configured (kafka.clusters.* is empty)</span>
      {/if}
    </div>

    <div class="toolbar-status">
      {#if status}
        <span class="status-count">{users.length} {users.length === 1 ? 'identity' : 'identities'} tracked</span>
        {#if status.builtAt}
          <span class="toolbar-muted">synced {formatBuiltAt(status.builtAt)}</span>
        {/if}
        {#if status.error}
          <span class="status-chip error">sync failed: {status.error}</span>
        {/if}
      {/if}
    </div>
  </div>

  <main class="page-main">
    {#if loadingClusters}
      <div class="state-block">
        <span class="spinner"></span>
        <p>Loading clusters…</p>
      </div>
    {:else if clustersError}
      <div class="state-block">
        <div role="alert" class="alert-box">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
            <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <span>Failed to load clusters: {clustersError}</span>
          <button class="btn-retry" on:click={loadClusters}>Retry</button>
        </div>
      </div>
    {:else if clusters.length === 0}
      <div class="state-block">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--color-text-muted)" stroke-width="1.2" aria-hidden="true">
          <ellipse cx="12" cy="5" rx="9" ry="3"/>
          <path d="M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5"/>
          <path d="M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3"/>
        </svg>
        <p class="state-title">No clusters configured</p>
        <span class="toolbar-muted">Add an entry under kafka.clusters in application.yml</span>
      </div>
    {:else if loadingUsers}
      <div class="state-block">
        <span class="spinner"></span>
        <p>Loading ACL graph…</p>
      </div>
    {:else if usersError}
      <div class="state-block">
        <div role="alert" class="alert-box">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
            <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <span>Failed to load ACL users: {usersError}</span>
          <button class="btn-retry" on:click={loadUsers}>Retry</button>
        </div>
      </div>
    {:else if users.length === 0}
      <div class="state-block">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--color-text-muted)" stroke-width="1.2" aria-hidden="true">
          <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
        </svg>
        <p class="state-title">No ACL bindings found</p>
        <span class="toolbar-muted">Either this cluster has no ACLs, or the periodic refresh job hasn't run yet</span>
      </div>
    {:else}
      <div class="split">
        <div class="user-panel">
          <div class="user-panel-search">
            <label for="principal-filter" class="sr-only">Filter principals</label>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
            <input
              id="principal-filter"
              type="text"
              name="principal-filter"
              autocomplete="off"
              placeholder="Filter principals…"
              bind:value={searchQuery}
            />
          </div>
          <ul class="user-list">
            {#each filteredUsers as u (u.principal)}
              <li>
                <button
                  class="user-row"
                  class:active={u.principal === selectedPrincipal}
                  on:click={() => loadDetail(u.principal)}
                >
                  {u.principal}
                </button>
              </li>
            {/each}
          </ul>
          {#if filteredUsers.length === 0}
            <div class="user-panel-empty">No principal matches "{searchQuery}"</div>
          {/if}
        </div>

        <div class="detail-panel">
          {#if loadingDetail}
            <div class="state-block inline"><span class="spinner"></span></div>
          {:else if detailError}
            <div class="state-block inline">
              <div role="alert" class="alert-box"><span>Could not load detail: {detailError}</span></div>
            </div>
          {:else if detail}
            <HudCore {detail} clusterName={selectedClusterName} on:viewgroup={handleViewGroup} on:copied={handleCopied} />
          {/if}
        </div>
      </div>
    {/if}
  </main>
</div>

{#if openGroupPopup}
  <GroupStatePopup
    group={openGroupPopup.group}
    clusterName={selectedClusterName}
    on:close={() => (openGroupPopup = null)}
  />
{/if}

<div class="sr-only" aria-live="polite">{announcement}</div>

<style>
  .registry-page {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-height: 0;
    width: 100%;
    background: var(--color-bg);
    color: var(--color-text-primary);
  }

  .sr-only {
    position: absolute;
    width: 1px; height: 1px;
    padding: 0; margin: -1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    border: 0;
  }

  .toolbar {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 16px;
    padding: 10px 20px;
    border-bottom: 1px solid var(--color-border);
    background: var(--color-surface);
    flex-shrink: 0;
  }

  .toolbar-cluster {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .toolbar-label {
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--color-text-muted);
  }

  .cluster-select {
    background: var(--color-surface-sunken);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    color: var(--color-accent);
    font-family: var(--font-mono);
    font-size: 12.5px;
    font-weight: 600;
    padding: 5px 10px;
  }
  .cluster-select:focus-visible { outline: 1px solid var(--color-accent); }

  .toolbar-muted {
    font-size: 12px;
    color: var(--color-text-muted);
  }

  .toolbar-status {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-left: auto;
    font-size: 12px;
  }

  .status-count {
    color: var(--color-accent);
    font-weight: 500;
  }

  .status-chip {
    padding: 2px 9px;
    border-radius: var(--radius-full);
    font-size: 11px;
    font-weight: 600;
  }
  .status-chip.error {
    background: rgba(248, 113, 113, 0.12);
    color: var(--color-danger);
  }

  .page-main {
    display: flex;
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }

  .state-block {
    margin: auto;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    padding: 32px;
    text-align: center;
    color: var(--color-text-muted);
    font-size: 13.5px;
  }
  .state-block.inline { margin: auto; }

  .state-title {
    font-weight: 600;
    color: var(--color-text-primary);
    font-size: 14px;
  }

  .spinner {
    width: 30px;
    height: 30px;
    border: 3px solid var(--color-border);
    border-top-color: var(--color-accent);
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  @media (prefers-reduced-motion: reduce) { .spinner { animation-duration: 2s; } }

  .alert-box {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    padding: 20px;
    border: 1px solid rgba(248, 113, 113, 0.3);
    background: rgba(248, 113, 113, 0.08);
    border-radius: var(--radius-md);
    color: var(--color-danger);
    max-width: 380px;
  }

  .btn-retry {
    padding: 6px 16px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--color-border);
    background: var(--color-surface);
    color: var(--color-text-primary);
    font-size: 12.5px;
    font-weight: 500;
    cursor: pointer;
    transition: background var(--duration-fast) ease;
  }
  .btn-retry:hover { background: var(--color-surface-hover); }

  .split {
    display: flex;
    flex: 1;
    min-height: 0;
    width: 100%;
    overflow-y: auto;
  }

  .user-panel {
    width: 272px;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    border-right: 1px solid var(--color-border);
    overflow-y: auto;
  }

  .user-panel-search {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 14px 16px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
    color: var(--color-text-muted);
  }

  .user-panel-search input {
    flex: 1;
    min-width: 0;
    background: var(--color-surface-sunken);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    padding: 6px 10px;
    font-size: 12.5px;
    font-family: var(--font-mono);
    color: var(--color-text-primary);
  }
  .user-panel-search input:focus { outline: none; border-color: var(--color-accent); }

  .user-panel-empty {
    padding: 16px;
    font-size: 12.5px;
    color: var(--color-text-muted);
    font-style: italic;
  }

  .user-list {
    list-style: none;
  }

  .user-row {
    width: 100%;
    display: block;
    padding: 9px 16px;
    border: none;
    border-left: 2px solid transparent;
    background: none;
    cursor: pointer;
    text-align: left;
    font-family: var(--font-mono);
    font-size: 12.5px;
    font-weight: 500;
    color: var(--color-text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease, border-color var(--duration-fast) ease;
  }
  .user-row:hover { background: var(--color-surface-hover); color: var(--color-text-primary); }
  .user-row.active {
    background: var(--color-accent-light);
    border-left-color: var(--color-accent);
    color: var(--color-accent);
  }
  .user-row:focus-visible { outline: 1px solid var(--color-accent); outline-offset: -1px; }

  .detail-panel {
    flex: 1;
    min-width: 0;
    display: flex;
    padding: 20px;
  }
</style>
