<script>
  import { createEventDispatcher } from 'svelte';

  export let tabs; // [{ id, label }]
  export let active; // id of the selected tab
  export let label = 'Views';

  const dispatch = createEventDispatcher();
</script>

<div role="tablist" aria-label={label} class="tabs tabs-border">
  {#each tabs as t (t.id)}
    <button
      role="tab"
      id="tab-{t.id}"
      aria-selected={active === t.id}
      aria-controls="panel-{t.id}"
      class="tab"
      class:tab-active={active === t.id}
      on:click={() => dispatch('change', t.id)}
    >{t.label}</button>
  {/each}
</div>
