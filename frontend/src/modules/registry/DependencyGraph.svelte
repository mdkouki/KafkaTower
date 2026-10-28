<script>
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';

  export let clusterName;
  export let principal; // one instance per principal — the parent re-creates it via {#key}

  const dispatch = createEventDispatcher();

  let container;
  let cy = null;
  let loading = true;
  let error = null;
  let deps = null;
  let focus = null; // { title, topics } of the clicked edge

  onMount(async () => {
    try {
      const res = await fetch(`/api/registry/acl-users/dependencies?clusterName=${encodeURIComponent(clusterName)}&principal=${encodeURIComponent(principal)}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      deps = await res.json();
    } catch (e) {
      error = e.message;
    } finally {
      loading = false;
    }
  });

  $: related = deps ? deps.upstream.length + deps.downstream.length : 0;
  $: if (container && related > 0) draw(deps);

  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const short = (p) => p.replace(/^User:/, '');
  const label = (topics) => topics.length <= 2 ? topics.join(', ') : `${topics.length} topics`;

  async function draw(d) {
    // Cytoscape is only needed on this tab, so it is split into its own chunk.
    const { default: cytoscape } = await import('cytoscape');
    if (!container) return;
    cy?.destroy();

    const [accent, warning, text, muted, surface, border] =
      ['--color-accent', '--color-warning', '--color-text-primary', '--color-text-secondary', '--color-surface', '--color-border-strong'].map(css);
    // Explicit widths: 'label'-based sizing measures 0 on first paint and Cytoscape then skips the node.
    const w = (text) => Math.max(90, text.length * 7.5 + 28);
    const elements = [{ data: { id: 'self', label: short(d.principal), w: w(short(d.principal)), kind: 'self' }, position: { x: 0, y: 0 } }];
    for (const [kind, rows, x] of [['up', d.upstream, -340], ['down', d.downstream, 340]]) {
      rows.forEach((r, i) => {
        const id = `${kind}:${r.principal}`;
        const [source, target] = kind === 'up' ? [id, 'self'] : ['self', id];
        const [from, to] = kind === 'up' ? [r.principal, d.principal] : [d.principal, r.principal];
        elements.push(
          { data: { id, label: short(r.principal), w: w(short(r.principal)), kind, principal: r.principal }, position: { x, y: (i - (rows.length - 1) / 2) * 64 } },
          { data: { source, target, label: label(r.topics), topics: r.topics, title: `${short(from)} → ${short(to)}` } });
      });
    }

    cy = cytoscape({
      container, elements, layout: { name: 'preset', fit: true, padding: 30 }, minZoom: 0.3, maxZoom: 2.5, wheelSensitivity: 0.25,
      style: [
        { selector: 'node', style: { label: 'data(label)', color: text, 'font-size': 12, 'text-valign': 'center', shape: 'round-rectangle',
            width: 'data(w)', height: 34, 'background-color': surface, 'border-width': 1.5, 'border-color': border } },
        { selector: 'node[kind = "self"]', style: { 'border-color': accent, 'border-width': 3, 'font-weight': 'bold' } },
        { selector: 'node[kind = "up"]', style: { 'border-color': warning } },
        { selector: 'node[kind = "down"]', style: { 'border-color': accent } },
        { selector: 'edge', style: { width: 2, 'curve-style': 'bezier', 'line-color': muted, 'target-arrow-color': muted, 'target-arrow-shape': 'triangle',
            label: 'data(label)', color: muted, 'font-size': 10, 'text-background-color': surface, 'text-background-opacity': 1, 'text-background-padding': '3px' } },
        { selector: 'edge:selected', style: { width: 3.5, 'line-color': accent, 'target-arrow-color': accent, color: text } },
      ],
    });
    cy.maxZoom(1.2); // fit() must not blow up a small graph…
    cy.fit(undefined, 30);
    cy.maxZoom(2.5); // …but the user can still zoom in further
    cy.on('tap', 'node[principal]', (e) => dispatch('select', e.target.data('principal')));
    cy.on('tap', 'edge', (e) => (focus = e.target.data()));
    cy.on('tap', (e) => { if (e.target === cy) focus = null; });
    cy.on('mouseover', 'edge, node[principal]', () => (container.style.cursor = 'pointer'));
    cy.on('mouseout', 'edge, node', () => (container.style.cursor = ''));
  }

  onDestroy(() => cy?.destroy());
</script>

<div class="dep-wrap">
  {#if loading}
    <div class="dep-state"><span class="spinner"></span></div>
  {:else if error}
    <div class="dep-state"><div role="alert" class="alert-box"><span>Could not load dependencies: {error}</span></div></div>
  {:else if deps && related === 0}
    <div class="dep-state">
      <p class="dep-title">No related principals</p>
      <span class="dep-muted">No other principal produces to a topic this one consumes, or consumes from a topic it produces to.</span>
    </div>
  {:else if deps}
    <div class="dep-legend" aria-hidden="true">
      <span><i class="dot up"></i>Upstream — produces to topics {short(deps.principal)} consumes ({deps.upstream.length})</span>
      <span><i class="dot down"></i>Downstream — consumes topics {short(deps.principal)} produces to ({deps.downstream.length})</span>
    </div>
    <div class="dep-canvas" bind:this={container} role="img"
         aria-label="Dependency graph for {deps.principal}: {deps.upstream.length} upstream and {deps.downstream.length} downstream principals"></div>
    <div class="dep-focus" aria-live="polite">
      {#if focus}
        <strong>{focus.title}</strong>
        <ul>{#each focus.topics as t}<li>{t}</li>{/each}</ul>
      {:else}
        <span class="dep-muted">Click a link to list its topics · click a principal to open it · scroll to zoom, drag to pan</span>
      {/if}
    </div>

    <details class="dep-list">
      <summary>Accessible list view</summary>
      {#each [['Upstream', deps.upstream], ['Downstream', deps.downstream]] as [title, rows]}
        {#if rows.length}
          <h4>{title}</h4>
          <ul>
            {#each rows as r}
              <li><button class="link" on:click={() => dispatch('select', r.principal)}>{r.principal}</button> — {r.topics.join(', ')}</li>
            {/each}
          </ul>
        {/if}
      {/each}
    </details>
  {/if}
</div>

<style>
  .dep-wrap { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; gap: 10px; }
  .dep-canvas {
    flex: 1; min-height: 240px;
    background: var(--color-surface-sunken);
    border: 1px solid var(--color-border);
    border-radius: 10px;
  }
  .dep-state { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; text-align: center; padding: 40px; }
  .dep-title { font-weight: 600; color: var(--color-text-primary); margin: 0; }
  .dep-muted { color: var(--color-text-muted); font-size: 12px; }
  .dep-legend { display: flex; flex-wrap: wrap; gap: 16px; font-size: 12px; color: var(--color-text-secondary); }
  .dot { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 6px; border: 2px solid; }
  .dot.up { border-color: var(--color-warning); }
  .dot.down { border-color: var(--color-accent); }
  .dep-focus { font-size: 12px; color: var(--color-text-secondary); min-height: 36px; }
  .dep-focus strong { color: var(--color-text-primary); }
  .dep-focus ul { display: flex; flex-wrap: wrap; gap: 6px; list-style: none; padding: 0; margin: 6px 0 0; }
  .dep-focus li { background: var(--color-accent-light); color: var(--color-accent); border-radius: 6px; padding: 2px 8px; font-family: monospace; }
  .dep-list { font-size: 12px; color: var(--color-text-secondary); }
  .dep-list summary { cursor: pointer; }
  .dep-list h4 { margin: 8px 0 2px; }
  .link { background: none; border: 0; color: var(--color-accent); cursor: pointer; padding: 0; font: inherit; text-decoration: underline; }
</style>
