<script>
  import { onMount } from 'svelte';
  import { fade } from 'svelte/transition';
  import { motionParams } from './core/motion.js';
  import { authUser, authMode, initAuth, logout } from './core/auth.js';
  import { pages } from './modules/pages.js';
  import FeedbackPanel from './core/FeedbackPanel.svelte';
  import LlmSettingsPanel from './core/LlmSettingsPanel.svelte';
  import Chatbot from './core/Chatbot.svelte';
  import Login from './core/Login.svelte';

  let showFeedbackPanel = false;
  let showLlmSettings = false;

  let menuItems = [];
  let selectedMenuKey = null;

  onMount(async () => {
    await initAuth();
    if ($authUser !== false) {
      await loadMenu();
    }
  });

  async function loadMenu() {
    try {
      const res = await fetch('/api/ui/menu');
      if (res.status === 401 || res.status === 403) {
        authUser.set(false);
        return;
      }
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      menuItems = await res.json();
      if (menuItems.length > 0 && selectedMenuKey === null) {
        selectedMenuKey = menuItems[0].key;
      }
    } catch (e) {
      console.error('Failed to load menu:', e);
    }
  }

  $: activePage = selectedMenuKey ? pages[selectedMenuKey] : null;

  function initials(name) {
    if (!name) return '?';
    return name.trim().slice(0, 2).toUpperCase();
  }
</script>

{#if $authUser === null}
  <div class="auth-loading" transition:fade={motionParams({ duration: 200 })}>
    <div class="spinner"></div>
    <p>Authenticating…</p>
  </div>
{:else if $authUser === false}
  <Login authMode={$authMode} />
{:else}
<div class="shell">
  <aside class="sidebar">
    <div class="brand">
      <svg class="brand-mark" width="26" height="26" viewBox="0 0 28 28" fill="none">
        <rect width="28" height="28" rx="8" fill="var(--color-accent)"/>
        <rect x="9" y="12" width="10" height="11" rx="1" fill="#081019"/>
        <rect x="9" y="9" width="2.4" height="3" fill="#081019"/>
        <rect x="12.8" y="9" width="2.4" height="3" fill="#081019"/>
        <rect x="16.6" y="9" width="2.4" height="3" fill="#081019"/>
        <rect x="13" y="16" width="2" height="3" fill="var(--color-accent)"/>
        <line x1="14" y1="9" x2="14" y2="5" stroke="#081019" stroke-width="1.3" stroke-linecap="round"/>
        <circle cx="14" cy="5" r="1" fill="#081019"/>
      </svg>
      <div class="brand-text">
        <span class="brand-name">KafkaTower</span>
        <span class="brand-sub">Kafka operations</span>
      </div>
    </div>

    {#if menuItems.length > 1}
      <nav class="nav" aria-label="Pages">
        {#each menuItems as item}
          <button
            class="nav-item"
            class:active={selectedMenuKey === item.key}
            on:click={() => (selectedMenuKey = item.key)}
          >
            <span class="nav-dot" aria-hidden="true"></span>
            {item.label}
          </button>
        {/each}
      </nav>
    {/if}

    <div class="sidebar-spacer"></div>

    <div class="sidebar-footer">
      {#if $authUser && $authUser.role === 'ADMIN'}
        <button
          class="footer-item"
          class:active={showFeedbackPanel}
          on:click={() => (showFeedbackPanel = true)}
          aria-label="View chat feedback"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/>
            <path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>
          </svg>
          Feedback
        </button>
        <button
          class="footer-item"
          class:active={showLlmSettings}
          on:click={() => (showLlmSettings = true)}
          aria-label="LLM settings"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 2a10 10 0 1 0 0 20A10 10 0 0 0 12 2z"/>
            <path d="M12 8v4l3 3"/>
          </svg>
          LLM settings
        </button>
      {/if}

      {#if $authUser}
        <div class="user-card">
          <span class="user-avatar" aria-hidden="true">{initials($authUser.username)}</span>
          <span class="user-name">{$authUser.username}</span>
          <button class="logout-btn" on:click={logout} title="Logout" aria-label="Logout">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
              <polyline points="16 17 21 12 16 7"/>
              <line x1="21" y1="12" x2="9" y2="12"/>
            </svg>
          </button>
        </div>
      {/if}
    </div>
  </aside>

  <main class="content">
    {#if activePage}
      <svelte:component this={activePage} />
    {/if}
  </main>

  <Chatbot />
</div>
{/if}

{#if showFeedbackPanel}
  <FeedbackPanel onClose={() => (showFeedbackPanel = false)} />
{/if}

{#if showLlmSettings}
  <LlmSettingsPanel on:close={() => (showLlmSettings = false)} />
{/if}

<style>
  .auth-loading {
    display: flex;
    justify-content: center;
    align-items: center;
    height: 100vh;
    flex-direction: column;
    gap: 20px;
    background: var(--color-bg);
    color: var(--color-text-secondary);
  }

  .auth-loading .spinner {
    width: 40px;
    height: 40px;
    border: 3px solid var(--color-border);
    border-top-color: var(--color-accent);
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to { transform: rotate(360deg); }
  }
  @media (prefers-reduced-motion: reduce) {
    .spinner { animation-duration: 2s; }
  }

  .shell {
    display: flex;
    height: 100vh;
    overflow: hidden;
    background: var(--color-bg);
  }

  /* Sidebar */
  .sidebar {
    width: 232px;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    background: var(--color-surface);
    border-right: 1px solid var(--color-border);
    padding: 18px 14px;
    gap: 4px;
  }

  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 4px 6px 18px;
  }

  .brand-mark { flex-shrink: 0; }

  .brand-text {
    display: flex;
    flex-direction: column;
    line-height: 1.2;
    min-width: 0;
  }

  .brand-name {
    font-size: 14px;
    font-weight: 700;
    color: var(--color-text-primary);
  }

  .brand-sub {
    font-size: 11px;
    color: var(--color-text-muted);
  }

  .nav {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .nav-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 12px;
    border: none;
    background: transparent;
    border-radius: var(--radius-md);
    font-size: 13px;
    font-weight: 500;
    color: var(--color-text-secondary);
    text-align: left;
    cursor: pointer;
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease;
  }

  .nav-dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: currentColor;
    opacity: 0.4;
    flex-shrink: 0;
  }

  .nav-item:hover {
    background: var(--color-surface-hover);
    color: var(--color-text-primary);
  }

  .nav-item.active {
    background: var(--color-accent-light);
    color: var(--color-accent);
  }
  .nav-item.active .nav-dot { opacity: 1; }

  .sidebar-spacer { flex: 1; }

  .sidebar-footer {
    display: flex;
    flex-direction: column;
    gap: 2px;
    border-top: 1px solid var(--color-border);
    padding-top: 10px;
  }

  .footer-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 12px;
    border: none;
    background: transparent;
    border-radius: var(--radius-md);
    font-size: 12.5px;
    font-weight: 500;
    color: var(--color-text-muted);
    cursor: pointer;
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease;
  }

  .footer-item:hover,
  .footer-item.active {
    background: var(--color-surface-hover);
    color: var(--color-text-primary);
  }

  .user-card {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 8px 2px;
    margin-top: 6px;
  }

  .user-avatar {
    width: 26px;
    height: 26px;
    border-radius: var(--radius-full);
    background: var(--gradient-accent);
    color: #081019;
    font-size: 10.5px;
    font-weight: 700;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .user-name {
    font-size: 12.5px;
    font-weight: 500;
    color: var(--color-text-secondary);
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .logout-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text-muted);
    cursor: pointer;
    flex-shrink: 0;
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease;
  }

  .logout-btn:hover {
    background: var(--color-surface-hover);
    color: var(--color-danger);
  }

  /* Content */
  .content {
    flex: 1;
    min-width: 0;
    display: flex;
    overflow: hidden;
  }

  @media (max-width: 720px) {
    .shell { flex-direction: column; }
    .sidebar {
      width: 100%;
      flex-direction: row;
      align-items: center;
      padding: 8px 12px;
      gap: 10px;
      overflow-x: auto;
    }
    .brand { padding: 4px 8px 4px 0; border-right: 1px solid var(--color-border); }
    .brand-sub { display: none; }
    .nav { flex-direction: row; }
    .sidebar-spacer { display: none; }
    .sidebar-footer { flex-direction: row; border-top: none; padding-top: 0; margin-left: auto; }
    .footer-item span { display: none; }
    .footer-item { padding: 8px; }
    .user-card { margin-top: 0; padding: 0; }
    .user-name { display: none; }
  }
</style>
