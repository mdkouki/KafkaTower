<script>
  import { createEventDispatcher } from 'svelte';
  import { fly } from 'svelte/transition';
  import { motionParams } from '../../core/motion.js';
  import HudPanel from './HudPanel.svelte';
  import AclGroupsPanel from './AclGroupsPanel.svelte';

  export let detail; // { principal, consumeTopics, produceTopics, consumerGroups, transactionalIds }
  export let clusterName;

  const dispatch = createEventDispatcher();

  let copiedPrincipal = false;
  let copiedTimeout;

  let activeIndex = 0;
  // A fresh principal always opens on the first card rather than remembering
  // whatever was on top for the previously selected user.
  $: if (detail && detail.principal) activeIndex = 0;

  $: categories = detail ? [
    { key: 'consume', title: 'Consume topics', items: detail.consumeTopics, color: 'var(--color-accent)' },
    { key: 'produce', title: 'Produce topics', items: detail.produceTopics, color: 'var(--color-warning)' },
    { key: 'groups', title: 'Consumer groups', items: detail.consumerGroups, color: 'var(--color-success)' },
    { key: 'txn', title: 'Transactional IDs', items: detail.transactionalIds, color: 'var(--color-info)' },
  ] : [];

  async function copyPrincipal() {
    try {
      await navigator.clipboard.writeText(detail.principal);
    } catch (_) {
      return;
    }
    copiedPrincipal = true;
    clearTimeout(copiedTimeout);
    copiedTimeout = setTimeout(() => (copiedPrincipal = false), 1400);
    dispatch('copied', detail.principal);
  }

  function forwardViewGroup(e) {
    dispatch('viewgroup', e.detail);
  }

  function forwardCopied(e) {
    dispatch('copied', e.detail);
  }
</script>

{#if detail}
  {#key detail.principal}
    <div class="console" in:fly={motionParams({ y: 8, duration: 200 })}>
      <div class="identity">
        <div class="identity-icon" aria-hidden="true">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="8" r="4"/>
            <path d="M4 21v-1a8 8 0 0 1 16 0v1"/>
          </svg>
        </div>
        <div class="identity-main">
          <span class="identity-name">{detail.principal}</span>
          <span class="identity-cluster">{clusterName}</span>
        </div>
        <button class="copy-btn" class:copied={copiedPrincipal} on:click={copyPrincipal}>
          {#if copiedPrincipal}
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
            Copied
          {:else}
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
            Copy
          {/if}
        </button>
      </div>

      <!-- Tabs pick which card is showing. Only the active card is visible —
           the rest stay mounted (so switching is instant, no re-fetching state)
           but fully hidden, not peeking out behind it. -->
      <div class="tabs" role="tablist" aria-label="ACL category">
        {#each categories as cat, i}
          <button
            class="tab"
            class:active={i === activeIndex}
            style="--tab-color: {cat.color}"
            role="tab"
            aria-selected={i === activeIndex}
            on:click={() => (activeIndex = i)}
          >
            {cat.title}
            <span class="tab-count">{cat.items.length}</span>
          </button>
        {/each}
      </div>

      <div class="stack">
        {#each categories as cat, i (cat.key)}
          <div class="stack-card" class:front={i === activeIndex} inert={i !== activeIndex}>
            {#if cat.key === 'groups'}
              <AclGroupsPanel
                title={cat.title}
                grants={cat.items}
                color={cat.color}
                {clusterName}
                on:viewgroup={forwardViewGroup}
              />
            {:else}
              <HudPanel
                title={cat.title}
                items={cat.items}
                color={cat.color}
                on:copied={forwardCopied}
              />
            {/if}
          </div>
        {/each}
      </div>
    </div>
  {/key}
{/if}

<style>
  .console {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-lg);
    width: 100%;
    padding: 18px;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .identity {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
    background: var(--color-surface-sunken);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    flex-wrap: wrap;
  }

  .identity-icon {
    width: 34px;
    height: 34px;
    border-radius: var(--radius-md);
    background: var(--color-accent-light);
    color: var(--color-accent);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .identity-main {
    display: flex;
    flex-direction: column;
    min-width: 0;
    flex: 1;
  }

  .identity-name {
    font-family: var(--font-mono);
    font-size: 15px;
    font-weight: 600;
    color: var(--color-text-primary);
    word-break: break-word;
  }

  .identity-cluster {
    font-size: 11.5px;
    color: var(--color-text-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }

  .copy-btn {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 7px 12px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-surface);
    color: var(--color-text-secondary);
    font-size: 12px;
    font-weight: 500;
    cursor: pointer;
    flex-shrink: 0;
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease, border-color var(--duration-fast) ease;
  }
  .copy-btn:hover { background: var(--color-surface-hover); color: var(--color-text-primary); }
  .copy-btn:focus-visible { outline: 1px solid var(--color-accent); }
  .copy-btn.copied { color: var(--color-success); border-color: var(--color-success); }

  .tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .tab {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 7px 14px;
    background: var(--color-surface-sunken);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-full);
    color: var(--color-text-secondary);
    font-size: 12.5px;
    font-weight: 500;
    cursor: pointer;
    transition: background var(--duration-fast) ease, border-color var(--duration-fast) ease, color var(--duration-fast) ease;
  }
  .tab:hover { border-color: var(--tab-color); color: var(--color-text-primary); }
  .tab:focus-visible { outline: 1px solid var(--tab-color); outline-offset: 1px; }
  .tab.active {
    background: color-mix(in srgb, var(--tab-color) 14%, var(--color-surface-sunken));
    border-color: var(--tab-color);
    color: var(--color-text-primary);
  }

  .tab-count {
    font-family: var(--font-mono);
    font-size: 11px;
    opacity: 0.75;
  }

  /* Only the active card renders — no peeking cards behind it. Every card
     stays mounted (absolutely positioned, opacity-hidden) so switching tabs
     is instant and doesn't reset scroll position or copy state. */
  .stack {
    position: relative;
    height: 420px;
  }

  .stack-card {
    position: absolute;
    inset: 0;
    opacity: 0;
    transition: opacity var(--duration-base) ease;
  }
  .stack-card:not(.front) { pointer-events: none; }
  .stack-card.front { opacity: 1; }
  .stack-card :global(.panel) { height: 100%; }

  @media (prefers-reduced-motion: reduce) {
    .stack-card { transition: none; }
  }

  @media (max-width: 640px) {
    .identity { padding: 10px 12px; }
    .identity-name { font-size: 13px; }
    .stack { height: 320px; }
  }
</style>
