<script>
  import { createEventDispatcher } from 'svelte';

  export let title;
  export let items = [];
  export let color; // css color value, e.g. 'var(--color-accent)'

  const dispatch = createEventDispatcher();

  let copiedItem = null;
  let copiedTimeout;
  let expanded = false;
  const VISIBLE_CAP = 30;

  async function copy(item) {
    try {
      await navigator.clipboard.writeText(item);
    } catch (_) {
      return;
    }
    copiedItem = item;
    clearTimeout(copiedTimeout);
    copiedTimeout = setTimeout(() => (copiedItem = null), 1400);
    dispatch('copied', item);
  }

  $: visibleItems = expanded ? items : items.slice(0, VISIBLE_CAP);
  $: hiddenCount = items.length - visibleItems.length;
</script>

<section class="panel" style="--panel-color: {color}">
  <header class="panel-head">
    <span class="panel-indicator"></span>
    <h2 class="panel-title">{title}</h2>
    <span class="panel-count">{items.length}</span>
  </header>

  {#if items.length === 0}
    <p class="panel-empty">Nothing here yet</p>
  {:else}
    <ul class="panel-list">
      {#each visibleItems as item (item)}
        <li>
          <button class="row" on:click={() => copy(item)} title="Copy to clipboard">
            <span class="row-label">{item}</span>
            <span class="row-status" class:copied={copiedItem === item}>
              {#if copiedItem === item}
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                Copied
              {:else}
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                Copy
              {/if}
            </span>
          </button>
        </li>
      {/each}
    </ul>
    {#if hiddenCount > 0}
      <button class="panel-more" on:click={() => (expanded = true)}>Show {hiddenCount} more</button>
    {/if}
  {/if}
</section>

<style>
  .panel {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    display: flex;
    flex-direction: column;
    min-height: 64px;
    overflow: hidden;
    position: relative;
  }

  .panel-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
  }

  .panel-indicator {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--panel-color, var(--color-accent));
    flex-shrink: 0;
  }

  .panel-title {
    font-size: 12.5px;
    font-weight: 600;
    color: var(--color-text-primary);
    flex: 1;
    min-width: 0;
  }

  .panel-count {
    font-family: var(--font-mono);
    font-size: 11px;
    color: var(--color-text-muted);
    background: var(--color-surface-sunken);
    border-radius: var(--radius-full);
    padding: 1px 8px;
  }

  .panel-empty {
    padding: 16px 14px;
    font-size: 12.5px;
    color: var(--color-text-muted);
  }

  .panel-list {
    list-style: none;
    overflow-y: auto;
    flex: 1;
    min-height: 0;
  }

  .panel-list li {
    border-bottom: 1px solid var(--color-border);
  }
  .panel-list li:last-child { border-bottom: none; }

  .row {
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 8px 14px;
    background: none;
    border: none;
    cursor: pointer;
    text-align: left;
    transition: background var(--duration-fast) ease;
  }
  .row:hover { background: var(--color-surface-hover); }
  .row:focus-visible { outline: 1px solid var(--color-accent); outline-offset: -1px; }

  .row-label {
    font-family: var(--font-mono);
    font-size: 12.5px;
    color: var(--color-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }

  .row-status {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    color: var(--color-text-muted);
    flex-shrink: 0;
    opacity: 0;
    transition: opacity var(--duration-fast) ease;
  }
  .row:hover .row-status,
  .row:focus-visible .row-status {
    opacity: 1;
  }
  .row-status.copied {
    opacity: 1;
    color: var(--color-success);
    font-weight: 600;
  }

  .panel-more {
    flex-shrink: 0;
    padding: 8px 14px;
    font-size: 11.5px;
    font-weight: 500;
    color: var(--color-accent);
    background: none;
    border: none;
    border-top: 1px solid var(--color-border);
    text-align: left;
    cursor: pointer;
    transition: background var(--duration-fast) ease;
  }
  .panel-more:hover { background: var(--color-surface-hover); }
  .panel-more:focus-visible { outline: 1px solid var(--color-accent); outline-offset: -1px; }
</style>
