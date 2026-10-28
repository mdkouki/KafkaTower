<script>
  import { onMount, onDestroy } from 'svelte';
  import { readSseEvents } from '../../core/sse.js';

  export let clusterName;
  export let topic;
  export let partitionCount = 1;

  const API = '/api/topics/content';
  const qs = `clusterName=${encodeURIComponent(clusterName)}&topic=${encodeURIComponent(topic)}`;

  // ── format & schema ────────────────────────────────────────────────────────
  let format = 'JSON';
  let schema = null; // { format, messageName, updatedAt } | null
  let schemaFile = null;
  let messageName = '';
  let schemaBusy = false;
  let schemaError = null;
  $: needsSchema = format === 'AVRO' || format === 'PROTOBUF';
  $: schemaUsable = schema && schema.format === format;

  onMount(async () => {
    try {
      const res = await fetch(`${API}/schema?${qs}`);
      if (res.ok) {
        schema = await res.json();
        format = schema.format;
        messageName = schema.messageName || '';
      }
    } catch { /* no stored schema */ }
  });

  async function uploadSchema() {
    schemaBusy = true;
    schemaError = null;
    try {
      const form = new FormData();
      form.append('file', schemaFile);
      const extra = `&format=${format}` + (messageName ? `&messageName=${encodeURIComponent(messageName)}` : '');
      const res = await fetch(`${API}/schema?${qs}${extra}`, { method: 'PUT', body: form });
      if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
      schema = await res.json();
      schemaFile = null;
    } catch (e) {
      schemaError = e.message;
    } finally {
      schemaBusy = false;
    }
  }

  async function removeSchema() {
    await fetch(`${API}/schema?${qs}`, { method: 'DELETE' });
    schema = null;
  }

  // ── search ─────────────────────────────────────────────────────────────────
  let mode = 'filter'; // 'filter' | 'offset'
  let key = { op: 'EQUALS', text: '' };
  let header = { key: '', op: 'EQUALS', text: '' };
  let value = { op: 'CONTAINS', text: '' };
  let partition = ''; // '' = any (filter mode)
  let offset = 0;
  let maxResults = 200;
  let count = 1;

  let rows = [];
  let expanded = new Set();
  let running = false;
  let progress = null; // { scanned, matched }
  let doneMessage = null;
  let error = null;
  let controller = null;

  function buildRequest() {
    const base = { clusterName, topic, format };
    if (mode === 'offset') {
      return { ...base, partition: Number(partition || 0), offset: Number(offset), maxResults: Number(count) };
    }
    return {
      ...base,
      partition: partition === '' ? null : Number(partition),
      key: key.text ? key : null,
      header: header.key ? header : null,
      value: value.text ? value : null,
      maxResults: Number(maxResults),
    };
  }

  async function search() {
    controller?.abort();
    controller = new AbortController();
    rows = [];
    expanded = new Set();
    progress = doneMessage = error = null;
    running = true;
    try {
      const res = await fetch(`${API}/search`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildRequest()),
        signal: controller.signal,
      });
      if (res.status === 403) throw new Error('Reading topic content is not permitted for your role.');
      if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
      await readSseEvents(res, (data) => {
        const ev = JSON.parse(data);
        if (ev.type === 'records') rows = [...rows, ...ev.records];
        else if (ev.type === 'progress') progress = { scanned: ev.scanned, matched: ev.matched };
        else if (ev.type === 'done') { progress = { scanned: ev.scanned, matched: ev.matched }; doneMessage = ev.message || null; }
        else if (ev.type === 'error') error = ev.message;
      });
    } catch (e) {
      if (e.name !== 'AbortError') error = e.message;
    } finally {
      running = false;
    }
  }

  function stop() {
    controller?.abort();
  }

  onDestroy(stop);

  // ── display helpers ────────────────────────────────────────────────────────
  const rowId = (r) => `${r.partition}:${r.offset}`;
  const fmtTime = (ts) => new Date(ts).toLocaleString();

  function toggle(r) {
    const id = rowId(r);
    expanded.has(id) ? expanded.delete(id) : expanded.add(id);
    expanded = expanded;
  }

  function pretty(text) {
    try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; }
  }
</script>

<div class="flex flex-1 min-h-0 min-w-0 flex-col gap-3 overflow-y-auto">
  <!-- format + schema -->
  <div class="flex flex-wrap items-end gap-3">
    <fieldset class="fieldset">
      <legend class="fieldset-legend">Value format</legend>
      <select class="select select-sm" bind:value={format} aria-label="Value format">
        <option value="STRING">String</option>
        <option value="JSON">JSON</option>
        <option value="AVRO">Avro</option>
        <option value="PROTOBUF">Protobuf</option>
      </select>
    </fieldset>

    {#if needsSchema}
      <fieldset class="fieldset">
        <legend class="fieldset-legend">{format === 'AVRO' ? 'Schema (.avsc)' : 'Descriptor set (.desc)'}</legend>
        <input type="file" class="file-input file-input-sm" aria-label="Schema file"
               accept={format === 'AVRO' ? '.avsc,.json' : '.desc,.pb,.bin'}
               on:change={(e) => (schemaFile = e.target.files[0] || null)} />
      </fieldset>
      {#if format === 'PROTOBUF'}
        <fieldset class="fieldset">
          <legend class="fieldset-legend">Message name</legend>
          <input class="input input-sm" placeholder="e.g. shop.Order" bind:value={messageName} />
        </fieldset>
      {/if}
      <button class="btn btn-sm" disabled={!schemaFile || schemaBusy} on:click={uploadSchema}>
        {#if schemaBusy}<span class="loading loading-spinner loading-xs"></span>{/if}Upload
      </button>
      {#if schemaUsable}
        <span class="badge badge-success badge-soft">Schema stored · {new Date(schema.updatedAt).toLocaleDateString()}</span>
        <button class="btn btn-ghost btn-xs" on:click={removeSchema}>Remove</button>
      {:else}
        <span class="badge badge-warning badge-soft">No schema — values shown as plain text</span>
      {/if}
    {/if}
  </div>
  {#if schemaError}<div role="alert" class="alert alert-error alert-soft py-2 text-sm">{schemaError}</div>{/if}

  <!-- search form -->
  <div class="flex flex-col gap-2 rounded-box border border-base-300 p-3">
    <div class="join">
      <button class="btn btn-sm join-item" class:btn-primary={mode === 'filter'} on:click={() => (mode = 'filter')}>Search content</button>
      <button class="btn btn-sm join-item" class:btn-primary={mode === 'offset'} on:click={() => (mode = 'offset')}>Partition &amp; offset</button>
    </div>

    <form class="flex flex-col gap-2" on:submit|preventDefault={search}>
      {#if mode === 'filter'}
        <div class="flex flex-wrap items-center gap-2">
          <span class="w-16 text-sm opacity-70">Key</span>
          <select class="select select-sm w-28" bind:value={key.op} aria-label="Key match"><option value="EQUALS">equals</option><option value="CONTAINS">contains</option></select>
          <input class="input input-sm flex-1 min-w-40" placeholder="key" bind:value={key.text} aria-label="Key text" />
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <span class="w-16 text-sm opacity-70">Header</span>
          <input class="input input-sm w-40" placeholder="header key" bind:value={header.key} aria-label="Header key" />
          <select class="select select-sm w-28" bind:value={header.op} aria-label="Header value match"><option value="EQUALS">equals</option><option value="CONTAINS">contains</option></select>
          <input class="input input-sm flex-1 min-w-40" placeholder="header value (optional)" bind:value={header.text} aria-label="Header value" />
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <span class="w-16 text-sm opacity-70">Value</span>
          <select class="select select-sm w-28" bind:value={value.op} aria-label="Value match"><option value="EQUALS">equals</option><option value="CONTAINS">contains</option></select>
          <input class="input input-sm flex-1 min-w-40" placeholder="text in the (decoded) value" bind:value={value.text} aria-label="Value text" />
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <span class="w-16 text-sm opacity-70">Scope</span>
          <select class="select select-sm w-36" bind:value={partition} aria-label="Partition">
            <option value="">All partitions</option>
            {#each Array(partitionCount) as _, p}<option value={p}>Partition {p}</option>{/each}
          </select>
          <label class="flex items-center gap-2 text-sm opacity-70">Max results
            <input type="number" min="1" max="1000" class="input input-sm w-24" bind:value={maxResults} />
          </label>
          <span class="text-xs opacity-50">Newest records first. All filled fields must match.</span>
        </div>
      {:else}
        <div class="flex flex-wrap items-center gap-2">
          <select class="select select-sm w-36" bind:value={partition} aria-label="Partition">
            {#each Array(partitionCount) as _, p}<option value={p}>Partition {p}</option>{/each}
          </select>
          <label class="flex items-center gap-2 text-sm opacity-70">Offset
            <input type="number" min="0" class="input input-sm w-32" bind:value={offset} />
          </label>
          <label class="flex items-center gap-2 text-sm opacity-70">Records
            <input type="number" min="1" max="1000" class="input input-sm w-24" bind:value={count} />
          </label>
        </div>
      {/if}

      <div class="flex items-center gap-3">
        {#if running}
          <button type="button" class="btn btn-sm btn-error" on:click={stop}>Stop</button>
        {:else}
          <button type="submit" class="btn btn-sm btn-primary">Search</button>
        {/if}
        <span class="text-sm opacity-70" aria-live="polite">
          {#if progress}Scanned {progress.scanned.toLocaleString()} · matched {progress.matched.toLocaleString()}{/if}
          {#if doneMessage} · {doneMessage}{/if}
        </span>
      </div>
      {#if running}<progress class="progress progress-primary w-full" aria-label="Searching"></progress>{/if}
    </form>
  </div>

  {#if error}<div role="alert" class="alert alert-error alert-soft py-2 text-sm">{error}</div>{/if}

  <!-- results -->
  {#if rows.length}
    <div class="overflow-x-auto rounded-box border border-base-300">
      <table class="table table-xs table-pin-rows">
        <thead>
          <tr><th>Part.</th><th>Offset</th><th>Time</th><th>Key</th><th>Value</th></tr>
        </thead>
        <tbody>
          {#each rows as r (rowId(r))}
            <tr class="hover cursor-pointer" tabindex="0" aria-expanded={expanded.has(rowId(r))}
                on:click={() => toggle(r)} on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), toggle(r))}>
              <td>{r.partition}</td>
              <td class="font-mono">{r.offset}</td>
              <td class="whitespace-nowrap">{fmtTime(r.timestamp)}</td>
              <td class="font-mono max-w-48 truncate">{r.key ?? ''}</td>
              <td class="font-mono max-w-xl truncate">
                {#if r.value === null}<span class="badge badge-ghost badge-sm">tombstone</span>{:else}{r.value}{/if}
                {#if r.headers.length}<span class="badge badge-outline badge-sm ml-1">{r.headers.length} hdr</span>{/if}
                {#if needsSchema && !r.decoded}<span class="badge badge-warning badge-soft badge-sm ml-1">raw</span>{/if}
              </td>
            </tr>
            {#if expanded.has(rowId(r))}
              <tr>
                <td colspan="5" class="bg-base-200">
                  {#if r.headers.length}
                    <div class="mb-2 flex flex-wrap gap-1">
                      {#each r.headers as h}<span class="badge badge-outline font-mono">{h.key}={h.value}</span>{/each}
                    </div>
                  {/if}
                  <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all text-xs">{r.value === null ? '(null value)' : pretty(r.value)}</pre>
                  {#if r.truncated}<span class="badge badge-warning badge-soft badge-sm">truncated at 64 KB</span>{/if}
                </td>
              </tr>
            {/if}
          {/each}
        </tbody>
      </table>
    </div>
  {:else if !running && progress}
    <p class="text-sm opacity-70">No matching records.</p>
  {/if}
</div>
