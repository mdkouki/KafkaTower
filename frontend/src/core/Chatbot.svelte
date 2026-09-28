<script>
  import { afterUpdate } from 'svelte';
  import { fade, fly, scale } from 'svelte/transition';
  import { motionParams } from './motion.js';
  import { readSseEvents } from './sse.js';
  import { marked } from 'marked';
  import DOMPurify from 'dompurify';
  import hljs from 'highlight.js/lib/core';
  import bash from 'highlight.js/lib/languages/bash';
  import json from 'highlight.js/lib/languages/json';
  import javascript from 'highlight.js/lib/languages/javascript';
  import java from 'highlight.js/lib/languages/java';
  import sql from 'highlight.js/lib/languages/sql';
  import yaml from 'highlight.js/lib/languages/yaml';
  import properties from 'highlight.js/lib/languages/properties';
  import xml from 'highlight.js/lib/languages/xml';
  import plaintext from 'highlight.js/lib/languages/plaintext';
  import 'highlight.js/styles/github-dark.css';

  // Lazy-loaded: mermaid (~1.4MB) and chart.js are only fetched the first time
  // a message actually contains a ```mermaid or ```chart block.
  function lazy(loader) {
    let promise = null;
    return () => promise ?? (promise = loader());
  }
  const loadMermaid = lazy(() =>
    import('mermaid').then((mod) => {
      const mermaid = mod.default;
      mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'strict' });
      return mermaid;
    })
  );
  const loadChart = lazy(() => import('chart.js/auto').then((mod) => mod.Chart ?? mod.default));

  hljs.registerLanguage('bash', bash);
  hljs.registerLanguage('shell', bash);
  hljs.registerLanguage('json', json);
  hljs.registerLanguage('javascript', javascript);
  hljs.registerLanguage('java', java);
  hljs.registerLanguage('sql', sql);
  hljs.registerLanguage('yaml', yaml);
  hljs.registerLanguage('properties', properties);
  hljs.registerLanguage('xml', xml);
  hljs.registerLanguage('plaintext', plaintext);

  const richRenderer = new marked.Renderer();
  richRenderer.code = ({ text, lang }) => {
    const language = (lang || '').trim().toLowerCase();
    if (language === 'mermaid') {
      const id = 'mmd-' + generateUUID();
      return `<div class="mermaid-block clickable" id="${id}" title="Click to enlarge" data-mermaid-src="${encodeURIComponent(text)}"><span class="mermaid-loading">Rendering diagram…</span></div>`;
    }
    if (language === 'chart') {
      const id = 'chart-' + generateUUID();
      return `<div class="chart-block"><canvas id="${id}" class="clickable" title="Click to enlarge" data-chart-spec="${encodeURIComponent(text)}"></canvas></div>`;
    }
    const known = hljs.getLanguage(language) ? language : 'plaintext';
    const highlighted = hljs.highlight(text, { language: known }).value;
    return `<pre><code class="hljs language-${known}">${highlighted}</code></pre>`;
  };
  marked.use({ renderer: richRenderer, gfm: true, breaks: false });

  // Shared between hydration (in-bubble, small) and the lightbox (full-size) renders.
  async function renderMermaidInto(el, src, renderId) {
    try {
      const mermaid = await loadMermaid();
      const { svg } = await mermaid.render(renderId, src);
      el.innerHTML = svg;
    } catch (e) {
      el.innerHTML = `<pre class="mermaid-error">Diagram error: ${(e && e.message) || e}</pre>`;
    }
  }

  async function renderChartInto(canvas, spec) {
    spec.options = { ...(spec.options || {}), responsive: true, maintainAspectRatio: false };
    const Chart = await loadChart();
    return new Chart(canvas, spec);
  }

  function parseChartSpec(canvas) {
    return JSON.parse(decodeURIComponent(canvas.getAttribute('data-chart-spec') || '{}'));
  }

  let chatMessagesEl;

  // Renders mermaid diagrams and chart.js graphs from their placeholder divs,
  // skipping any bubble still mid-stream so we never parse a half-written fence.
  async function hydrateRichContent() {
    if (!chatMessagesEl) return;
    const isStreaming = (el) => el.closest('.msg-bubble')?.querySelector('.cursor');

    const mermaidEls = chatMessagesEl.querySelectorAll('.mermaid-block:not([data-hydrated])');
    for (const el of mermaidEls) {
      if (isStreaming(el)) continue;
      el.setAttribute('data-hydrated', '1');
      const src = decodeURIComponent(el.getAttribute('data-mermaid-src') || '');
      await renderMermaidInto(el, src, el.id + '-svg');
    }

    const chartEls = chatMessagesEl.querySelectorAll('canvas[data-chart-spec]:not([data-hydrated])');
    for (const canvas of chartEls) {
      if (isStreaming(canvas)) continue;
      canvas.setAttribute('data-hydrated', '1');
      try {
        await renderChartInto(canvas, parseChartSpec(canvas));
      } catch (e) {
        const err = document.createElement('div');
        err.className = 'chart-error';
        err.textContent = `Chart error: ${(e && e.message) || e}`;
        canvas.replaceWith(err);
      }
    }
  }

  // Full-size lightbox for a clicked diagram/chart, rendered outside the chat panel.
  // Click wiring is delegated on .chat-messages (see handleRichContentClick) rather than
  // attaching a listener per rendered element, since elements are injected via {@html}.
  let lightbox = null; // { type: 'mermaid', src } | { type: 'chart', spec }
  let lightboxChartInstance = null;

  function openLightbox(content) {
    lightbox = content;
  }

  function closeLightbox() {
    lightbox = null;
  }

  function handleLightboxKeydown(e) {
    if (e.key === 'Escape') closeLightbox();
  }

  function handleRichContentClick(e) {
    const mermaidEl = e.target.closest('.mermaid-block[data-mermaid-src]');
    if (mermaidEl) {
      openLightbox({ type: 'mermaid', src: decodeURIComponent(mermaidEl.getAttribute('data-mermaid-src')) });
      return;
    }
    const canvas = e.target.closest('canvas[data-chart-spec]');
    if (canvas) {
      try {
        openLightbox({ type: 'chart', spec: parseChartSpec(canvas) });
      } catch (_) {
        // malformed spec — ignore the click rather than open a broken lightbox
      }
    }
  }

  // Svelte action: mounts a full-size render of the lightbox content into `node`.
  function lightboxRender(node, content) {
    let cancelled = false;

    async function render() {
      if (content.type === 'mermaid') {
        await renderMermaidInto(node, content.src, 'lightbox-mermaid-' + generateUUID());
      } else if (content.type === 'chart') {
        const canvas = document.createElement('canvas');
        node.appendChild(canvas);
        const instance = await renderChartInto(canvas, content.spec);
        if (!cancelled) lightboxChartInstance = instance;
        else instance.destroy();
      }
    }
    render();

    return {
      destroy() {
        cancelled = true;
        if (lightboxChartInstance) {
          lightboxChartInstance.destroy();
          lightboxChartInstance = null;
        }
      }
    };
  }

  function generateUUID() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      return crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
      const r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
  }

  const STATUS_PREFIX = '__status__:';

  let isOpen = false;
  let messages = [];
  let inputText = '';
  let isLoading = false;
  let statusText = '';
  let sessionId = generateUUID();
  let messagesEndEl;
  let inputEl;
  let shouldScrollToBottom = false;

  // Streaming buffer: accumulate chunks between rAF ticks to avoid per-token Svelte re-renders
  let streamingBuffer = '';
  let streamingMsgId = null;
  let rafScheduled = false;
  let lastMarkdownRender = 0;
  const MARKDOWN_THROTTLE_MS = 300;

  function flushStreamingBuffer() {
    rafScheduled = false;
    if (!streamingMsgId || !streamingBuffer) return;
    const chunk = streamingBuffer;
    streamingBuffer = '';
    const now = Date.now();
    const renderMarkdownNow = now - lastMarkdownRender >= MARKDOWN_THROTTLE_MS;
    if (renderMarkdownNow) lastMarkdownRender = now;
    messages = messages.map(m => {
      if (m.id !== streamingMsgId) return m;
      const newText = m.text + chunk;
      return { ...m, text: newText, renderedHtml: renderMarkdownNow ? renderMarkdown(newText) : m.renderedHtml };
    });
    shouldScrollToBottom = true;
  }

  function scheduleFlush() {
    if (!rafScheduled) {
      rafScheduled = true;
      requestAnimationFrame(flushStreamingBuffer);
    }
  }

  let panelWidth = 380;
  let panelHeight = 520;
  let resizeStartX = 0;
  let resizeStartY = 0;
  let resizeStartW = 380;
  let resizeStartH = 520;
  let resizeEdge = '';

  function startResize(e, edge) {
    resizeEdge = edge;
    resizeStartX = e.clientX;
    resizeStartY = e.clientY;
    resizeStartW = panelWidth;
    resizeStartH = panelHeight;
    e.preventDefault();
    window.addEventListener('mousemove', doResize);
    window.addEventListener('mouseup', endResize);
  }

  function doResize(e) {
    if (resizeEdge.includes('h')) {
      panelWidth = Math.min(Math.max(resizeStartW + (resizeStartX - e.clientX), 320), 900);
    }
    if (resizeEdge.includes('v')) {
      panelHeight = Math.min(Math.max(resizeStartH + (resizeStartY - e.clientY), 300), 820);
    }
  }

  function endResize() {
    resizeEdge = '';
    window.removeEventListener('mousemove', doResize);
    window.removeEventListener('mouseup', endResize);
  }

  function renderMarkdown(text) {
    if (!text) return '';
    return DOMPurify.sanitize(marked.parse(text));
  }

  afterUpdate(() => {
    if (shouldScrollToBottom && messagesEndEl) {
      messagesEndEl.scrollIntoView({ behavior: 'smooth' });
      shouldScrollToBottom = false;
    }
    hydrateRichContent();
  });

  async function sendMessage() {
    const text = inputText.trim();
    if (!text || isLoading) return;

    inputText = '';
    const userMsgId = generateUUID();
    const assistantMsgId = generateUUID();

    messages = [
      ...messages,
      { id: userMsgId, role: 'user', text },
      { id: assistantMsgId, role: 'assistant', text: '', renderedHtml: '', streaming: true, userPrompt: text, feedback: null }
    ];
    isLoading = true;
    shouldScrollToBottom = true;
    streamingMsgId = assistantMsgId;
    streamingBuffer = '';
    lastMarkdownRender = 0;

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message: text })
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      await readSseEvents(response, (chunk) => appendToMessage(assistantMsgId, chunk));

      // Flush any remaining buffered chunks and do final markdown render
      flushStreamingBuffer();
      messages = messages.map(m =>
        m.id === assistantMsgId
          ? { ...m, streaming: false, renderedHtml: renderMarkdown(m.text) }
          : m
      );
    } catch (e) {
      flushStreamingBuffer();
      statusText = '';
      messages = messages.map(m =>
        m.id === assistantMsgId
          ? { ...m, text: `Error: ${e.message}`, streaming: false, error: true }
          : m
      );
    } finally {
      streamingMsgId = null;
      statusText = '';
      isLoading = false;
      shouldScrollToBottom = true;
    }
  }

  function appendToMessage(id, chunk) {
    if (chunk.startsWith(STATUS_PREFIX)) {
      statusText = chunk.slice(STATUS_PREFIX.length);
      return;
    }
    if (id === streamingMsgId) {
      // First real content clears the status line
      if (statusText) statusText = '';
      streamingBuffer += chunk;
      scheduleFlush();
    } else {
      messages = messages.map(m =>
        m.id === id ? { ...m, text: m.text + chunk } : m
      );
      shouldScrollToBottom = true;
    }
  }

  function handleKeydown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  }

  function clearChat() {
    messages = [];
    sessionId = generateUUID();
  }

  function buildSessionText(upToMsgId) {
    const lines = [];
    for (const m of messages) {
      if (m.role === 'user') {
        lines.push(`User: ${m.text}`);
      } else if (m.role === 'assistant' && !m.streaming && !m.error) {
        lines.push(`Assistant: ${m.text}`);
      }
      if (m.id === upToMsgId) break;
    }
    return lines.join('\n\n');
  }

  async function submitFeedback(msg, value) {
    // Optimistic UI update
    messages = messages.map(m => m.id === msg.id ? { ...m, feedback: value } : m);
    try {
      await fetch('/api/feedback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionId,
          userPrompt: buildSessionText(msg.id),
          agentAnswer: msg.text,
          feedback: value
        })
      });
    } catch (_) {
      // Revert on failure
      messages = messages.map(m => m.id === msg.id ? { ...m, feedback: null } : m);
    }
  }

  function toggle() {
    isOpen = !isOpen;
    if (isOpen) {
      shouldScrollToBottom = true;
      setTimeout(() => inputEl?.focus(), 50);
    }
  }
</script>

<!-- Floating button -->
<button class="chat-fab" class:active={isOpen} on:click={toggle} aria-label="Open AI chat assistant">
  {#if isOpen}
    <!-- X icon -->
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
      <line x1="18" y1="6" x2="6" y2="18"/>
      <line x1="6" y1="6" x2="18" y2="18"/>
    </svg>
  {:else}
    <!-- Chat bubble icon -->
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
    </svg>
  {/if}
</button>

<!-- Chat panel -->
{#if isOpen}
  <div
    class="chat-panel"
    style:width="{panelWidth}px"
    style:height="{panelHeight}px"
    in:fly={motionParams({ y: 16, duration: 220 })}
    out:fade={motionParams({ duration: 150 })}
  >
    <div class="resize-handle resize-left"   on:mousedown={e => startResize(e, 'h')}></div>
    <div class="resize-handle resize-top"    on:mousedown={e => startResize(e, 'v')}></div>
    <div class="resize-handle resize-corner" on:mousedown={e => startResize(e, 'hv')}></div>
    <div class="chat-header">
      <div class="chat-title">
        <div class="chat-avatar">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="8" r="4"/>
            <path d="M4 20c0-4 3.6-7 8-7s8 3 8 7"/>
          </svg>
        </div>
        <div>
          <span class="chat-name">Kafka Assistant</span>
          <span class="chat-status">
            {#if isLoading}
              <span class="status-dot typing"></span> typing…
            {:else}
              <span class="status-dot online"></span> online
            {/if}
          </span>
        </div>
      </div>
      <div class="chat-header-actions">
        {#if messages.length > 0}
          <button class="icon-btn" on:click={clearChat} title="Clear conversation">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="3 6 5 6 21 6"/>
              <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
              <path d="M10 11v6M14 11v6"/>
              <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
            </svg>
          </button>
        {/if}
        <button class="icon-btn" on:click={toggle} title="Close">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>
    </div>

    <div class="chat-messages" bind:this={chatMessagesEl} on:click={handleRichContentClick}>
      {#if messages.length === 0}
        <div class="chat-empty">
          <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="var(--color-text-muted)" stroke-width="1.2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          </svg>
          <p>Ask me anything about your Kafka clusters, topics, consumer groups, or application dependencies.</p>
        </div>
      {:else}
        {#each messages as msg (msg.id)}
          <div class="msg-row" class:user={msg.role === 'user'} class:assistant={msg.role === 'assistant'}>
            {#if msg.role === 'assistant'}
              <div class="msg-icon">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="8" r="4"/>
                  <path d="M4 20c0-4 3.6-7 8-7s8 3 8 7"/>
                </svg>
              </div>
            {/if}
            <div class="msg-bubble" class:error={msg.error}>
              {#if msg.streaming && !msg.text}
                <span class="typing-dots">
                  <span></span><span></span><span></span>
                </span>
              {:else if msg.role === 'assistant'}
                <div class="msg-content">{@html msg.renderedHtml || msg.text}</div>
                {#if msg.streaming}<span class="cursor"></span>{/if}
                {#if !msg.streaming && !msg.error}
                  <div class="feedback-row">
                    <button
                      class="feedback-btn"
                      class:active={msg.feedback === 'UP'}
                      title="Good answer"
                      on:click|stopPropagation={() => submitFeedback(msg, msg.feedback === 'UP' ? null : 'UP')}
                    >
                      <svg width="12" height="12" viewBox="0 0 24 24" fill={msg.feedback === 'UP' ? 'currentColor' : 'none'} stroke="currentColor" stroke-width="2">
                        <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/>
                        <path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>
                      </svg>
                    </button>
                    <button
                      class="feedback-btn"
                      class:active={msg.feedback === 'DOWN'}
                      title="Bad answer"
                      on:click|stopPropagation={() => submitFeedback(msg, msg.feedback === 'DOWN' ? null : 'DOWN')}
                    >
                      <svg width="12" height="12" viewBox="0 0 24 24" fill={msg.feedback === 'DOWN' ? 'currentColor' : 'none'} stroke="currentColor" stroke-width="2" style="transform:rotate(180deg)">
                        <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/>
                        <path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>
                      </svg>
                    </button>
                  </div>
                {/if}
              {:else}
                {msg.text}
              {/if}
            </div>
          </div>
        {/each}
      {/if}
      <div bind:this={messagesEndEl}></div>
    </div>

    {#if statusText}
      <div class="chat-status-bar">
        <span class="status-spinner"></span>
        {statusText}
      </div>
    {/if}

    <div class="chat-input-area">
      <textarea
        bind:this={inputEl}
        class="chat-input"
        bind:value={inputText}
        on:keydown={handleKeydown}
        placeholder="Ask about Kafka topics, groups, dependencies…"
        rows="1"
        disabled={isLoading}
      ></textarea>
      <button
        class="send-btn"
        on:click={sendMessage}
        disabled={!inputText.trim() || isLoading}
        aria-label="Send message"
      >
        {#if isLoading}
          <span class="send-spinner"></span>
        {:else}
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <line x1="22" y1="2" x2="11" y2="13"/>
            <polygon points="22 2 15 22 11 13 2 9 22 2"/>
          </svg>
        {/if}
      </button>
    </div>
  </div>
{/if}

<svelte:window on:keydown={handleLightboxKeydown} />

<!-- Full-size diagram/chart lightbox -->
{#if lightbox}
  <!-- svelte-ignore a11y-click-events-have-key-events -->
  <!-- svelte-ignore a11y-no-static-element-interactions -->
  <div
    class="overlay"
    on:click={(e) => { if (e.target === e.currentTarget) closeLightbox(); }}
    transition:fade={motionParams({ duration: 180 })}
  >
    <div
      class="lightbox-content"
      role="dialog"
      aria-modal="true"
      aria-label="Enlarged diagram"
      transition:scale={motionParams({ start: 0.94, duration: 220 })}
    >
      <button class="close-btn" on:click={closeLightbox} aria-label="Close">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <line x1="18" y1="6" x2="6" y2="18"/>
          <line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      </button>
      <div class="lightbox-render" use:lightboxRender={lightbox}></div>
    </div>
  </div>
{/if}

<style>
  .chat-fab {
    position: fixed;
    bottom: 24px;
    right: 24px;
    width: 48px;
    height: 48px;
    border-radius: 50%;
    background: var(--gradient-accent);
    color: #081019;
    border: none;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: var(--shadow-lg);
    z-index: 300;
    transition: transform var(--duration-fast) var(--ease-spring), box-shadow var(--duration-base) ease, background var(--duration-base) ease;
  }

  .chat-fab:hover {
    transform: scale(1.06);
    box-shadow: var(--shadow-lg), var(--shadow-glow);
  }

  .chat-fab:active {
    transform: scale(0.96);
  }

  .chat-fab.active {
    background: var(--color-surface);
    color: var(--color-text-primary);
    border: 1px solid var(--color-border-strong);
    box-shadow: var(--shadow-md);
  }

  .chat-panel {
    position: fixed;
    bottom: 92px;
    right: 28px;
    background: var(--glass-bg);
    backdrop-filter: blur(var(--glass-blur));
    border: 1px solid var(--glass-border);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-lg), var(--shadow-glow);
    display: flex;
    flex-direction: column;
    z-index: 299;
    overflow: hidden;
  }

  /* Header */
  .chat-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 16px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
  }

  .chat-title {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .chat-avatar {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    background: var(--gradient-accent);
    display: flex;
    align-items: center;
    justify-content: center;
    color: #081019;
    flex-shrink: 0;
  }

  .chat-name {
    display: block;
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text-primary);
    line-height: 1.2;
  }

  .chat-status {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    color: var(--color-text-muted);
    margin-top: 1px;
  }

  .status-dot {
    display: inline-block;
    width: 6px;
    height: 6px;
    border-radius: 50%;
  }

  .status-dot.online { background: var(--color-success); }
  .status-dot.typing { background: var(--color-warning); animation: pulse 1s infinite; }

  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.4; }
  }

  .chat-header-actions {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .icon-btn {
    width: 28px;
    height: 28px;
    border: none;
    background: none;
    border-radius: var(--radius-md);
    cursor: pointer;
    color: var(--color-text-muted);
    display: flex;
    align-items: center;
    justify-content: center;
    transition: background var(--duration-fast) ease, color var(--duration-fast) ease, transform var(--duration-fast) var(--ease-spring);
  }

  .icon-btn:hover {
    background: var(--color-surface-hover);
    color: var(--color-text-primary);
    transform: scale(1.1);
  }

  .icon-btn:active {
    transform: scale(0.92);
  }

  /* Messages */
  .chat-messages {
    flex: 1;
    overflow-y: auto;
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .chat-empty {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    text-align: center;
    padding: 24px;
    color: var(--color-text-muted);
    height: 100%;
  }

  .chat-empty p {
    font-size: 13px;
    line-height: 1.6;
    color: var(--color-text-secondary);
    max-width: 260px;
  }

  .msg-row {
    display: flex;
    align-items: flex-end;
    gap: 8px;
  }

  .msg-row.user {
    flex-direction: row-reverse;
  }

  .msg-icon {
    width: 26px;
    height: 26px;
    border-radius: 50%;
    background: var(--gradient-accent);
    display: flex;
    align-items: center;
    justify-content: center;
    color: #081019;
    flex-shrink: 0;
  }

  .msg-bubble {
    max-width: 78%;
    padding: 9px 13px;
    border-radius: 14px;
    font-size: 13px;
    line-height: 1.55;
    white-space: pre-wrap;
    word-break: break-word;
  }

  /* Markdown content resets pre-wrap set on the bubble */
  .msg-bubble :global(.msg-content) {
    white-space: normal;
    line-height: 1.6;
  }

  .msg-bubble :global(.msg-content p) {
    margin: 0 0 0.55em;
  }
  .msg-bubble :global(.msg-content p:last-child) {
    margin-bottom: 0;
  }

  .msg-bubble :global(.msg-content h1),
  .msg-bubble :global(.msg-content h2),
  .msg-bubble :global(.msg-content h3),
  .msg-bubble :global(.msg-content h4) {
    font-weight: 700;
    line-height: 1.3;
    margin: 0.9em 0 0.3em;
    color: var(--color-text-primary);
  }
  .msg-bubble :global(.msg-content h1) { font-size: 15px; }
  .msg-bubble :global(.msg-content h2) { font-size: 14px; }
  .msg-bubble :global(.msg-content h3),
  .msg-bubble :global(.msg-content h4) { font-size: 13px; }

  .msg-bubble :global(.msg-content ul),
  .msg-bubble :global(.msg-content ol) {
    margin: 0.35em 0;
    padding-left: 1.4em;
  }
  .msg-bubble :global(.msg-content li) {
    margin: 0.2em 0;
  }

  .msg-bubble :global(.msg-content code) {
    font-family: var(--font-mono);
    font-size: 11.5px;
    background: rgba(255, 255, 255, 0.08);
    padding: 1px 5px;
    border-radius: 4px;
  }

  .msg-bubble :global(.msg-content pre) {
    background: var(--color-surface-sunken);
    border: 1px solid var(--color-border);
    border-radius: 8px;
    padding: 10px 13px;
    overflow-x: auto;
    margin: 0.55em 0;
  }
  .msg-bubble :global(.msg-content pre code) {
    background: none;
    padding: 0;
    color: var(--color-text-primary);
    font-size: 11.5px;
    border-radius: 0;
  }

  .msg-bubble :global(.msg-content blockquote) {
    border-left: 3px solid var(--color-accent);
    margin: 0.5em 0;
    padding: 0.15em 0.75em;
    opacity: 0.85;
  }

  .msg-bubble :global(.msg-content hr) {
    border: none;
    border-top: 1px solid var(--color-border);
    margin: 0.65em 0;
  }

  .msg-bubble :global(.msg-content strong) { font-weight: 700; }
  .msg-bubble :global(.msg-content em)     { font-style: italic; }

  .msg-bubble :global(.msg-content .mermaid-block),
  .msg-bubble :global(.msg-content .chart-block) {
    margin: 0.55em 0;
    padding: 8px;
    background: white;
    border: 1px solid var(--color-border);
    border-radius: 8px;
  }
  .msg-bubble :global(.msg-content .mermaid-block) {
    display: flex;
    align-items: center;
    justify-content: center;
    overflow-x: auto;
  }
  .msg-bubble :global(.msg-content .mermaid-block svg) {
    max-width: 100%;
    height: auto;
  }
  .msg-bubble :global(.msg-content .mermaid-block.clickable),
  .msg-bubble :global(.msg-content .chart-block canvas.clickable) {
    cursor: zoom-in;
    transition: opacity 0.15s;
  }
  .msg-bubble :global(.msg-content .mermaid-block.clickable:hover),
  .msg-bubble :global(.msg-content .chart-block canvas.clickable:hover) {
    opacity: 0.85;
  }
  .msg-bubble :global(.msg-content .mermaid-loading) {
    font-size: 11.5px;
    color: var(--color-text-muted);
  }
  .msg-bubble :global(.msg-content .mermaid-error),
  .msg-bubble :global(.msg-content .chart-error) {
    font-size: 11.5px;
    color: #b91c1c;
  }

  .msg-bubble :global(.msg-content .chart-block) {
    position: relative;
    height: 220px;
  }

  .msg-bubble :global(.msg-content table) {
    border-collapse: collapse;
    width: 100%;
    font-size: 12px;
    margin: 0.5em 0;
  }
  .msg-bubble :global(.msg-content th),
  .msg-bubble :global(.msg-content td) {
    border: 1px solid var(--color-border);
    padding: 5px 9px;
    text-align: left;
  }
  .msg-bubble :global(.msg-content th) {
    background: var(--color-surface-hover);
    font-weight: 600;
  }

  .msg-row.user .msg-bubble {
    background: var(--gradient-accent);
    color: #081019;
    border-bottom-right-radius: 4px;
  }

  .msg-row.assistant .msg-bubble {
    background: var(--color-surface-hover);
    color: var(--color-text-primary);
    border: 1px solid var(--color-border);
    border-bottom-left-radius: 4px;
  }

  .msg-bubble.error {
    background: rgba(248, 113, 113, 0.1);
    border-color: rgba(248, 113, 113, 0.35);
    color: var(--color-danger);
  }

  /* Typing dots animation */
  .typing-dots {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    padding: 2px 0;
  }

  .typing-dots span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--color-text-muted);
    animation: bounce 1.2s infinite;
  }

  .typing-dots span:nth-child(2) { animation-delay: 0.2s; }
  .typing-dots span:nth-child(3) { animation-delay: 0.4s; }

  @keyframes bounce {
    0%, 80%, 100% { transform: translateY(0); opacity: 0.5; }
    40% { transform: translateY(-5px); opacity: 1; }
  }

  /* Blinking cursor during stream */
  .cursor {
    display: inline-block;
    width: 2px;
    height: 13px;
    background: var(--color-accent);
    margin-left: 2px;
    vertical-align: middle;
    animation: blink 0.8s step-end infinite;
  }

  @keyframes blink {
    0%, 100% { opacity: 1; }
    50% { opacity: 0; }
  }

  /* Status bar */
  .chat-status-bar {
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 6px 16px;
    font-size: 11.5px;
    color: var(--color-text-muted);
    border-top: 1px solid var(--color-border);
    background: var(--color-surface);
    flex-shrink: 0;
  }

  .status-spinner {
    width: 10px;
    height: 10px;
    border: 1.5px solid var(--color-border);
    border-top-color: var(--color-accent);
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
    flex-shrink: 0;
  }

  /* Input area */
  .chat-input-area {
    display: flex;
    align-items: flex-end;
    gap: 8px;
    padding: 12px 16px;
    border-top: 1px solid var(--color-border);
    flex-shrink: 0;
  }

  .chat-input {
    flex: 1;
    padding: 9px 12px;
    border: 1px solid var(--color-border);
    border-radius: 10px;
    font-size: 13px;
    font-family: inherit;
    color: var(--color-text-primary);
    background: var(--color-surface-hover);
    resize: none;
    outline: none;
    line-height: 1.45;
    max-height: 100px;
    overflow-y: auto;
    transition: border-color 0.15s, box-shadow 0.15s;
  }

  .chat-input:focus {
    border-color: var(--color-accent);
    box-shadow: 0 0 0 3px var(--color-accent-light);
    background: white;
  }

  .chat-input:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .send-btn {
    width: 36px;
    height: 36px;
    border-radius: 10px;
    border: none;
    background: var(--gradient-accent);
    color: #081019;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    transition: opacity var(--duration-fast) ease, transform var(--duration-fast) var(--ease-spring);
  }

  .send-btn:disabled {
    opacity: 0.4;
    cursor: not-allowed;
    transform: none;
  }

  .send-btn:not(:disabled):hover {
    opacity: 0.88;
    transform: scale(1.05);
  }

  .send-spinner {
    width: 14px;
    height: 14px;
    border: 2px solid rgba(8, 16, 25, 0.3);
    border-top-color: #081019;
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
  }

  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  /* Resize handles */
  .resize-handle {
    position: absolute;
    z-index: 10;
  }

  .resize-left {
    left: 0;
    top: 10px;
    bottom: 10px;
    width: 5px;
    cursor: ew-resize;
    border-radius: 4px 0 0 4px;
  }

  .resize-top {
    top: 0;
    left: 10px;
    right: 10px;
    height: 5px;
    cursor: ns-resize;
    border-radius: 4px 4px 0 0;
  }

  .resize-corner {
    top: 0;
    left: 0;
    width: 14px;
    height: 14px;
    cursor: nw-resize;
    border-radius: 4px 0 0 0;
  }

  .resize-handle:hover,
  .resize-handle:active {
    background: var(--color-accent);
    opacity: 0.25;
  }

  /* Feedback buttons — visible only on msg-row hover */
  .feedback-row {
    display: flex;
    gap: 2px;
    margin-top: 6px;
    opacity: 0;
    transition: opacity 0.15s ease;
  }

  .msg-row:hover .feedback-row {
    opacity: 1;
  }

  .feedback-btn {
    width: 22px;
    height: 22px;
    border: 1px solid transparent;
    border-radius: 5px;
    background: none;
    cursor: pointer;
    color: var(--color-text-muted);
    display: flex;
    align-items: center;
    justify-content: center;
    transition: color var(--duration-fast) ease, background var(--duration-fast) ease, border-color var(--duration-fast) ease, transform var(--duration-fast) var(--ease-spring);
    padding: 0;
  }

  .feedback-btn:hover {
    background: var(--color-surface);
    border-color: var(--color-border);
    color: var(--color-text-primary);
    transform: scale(1.12);
  }

  .feedback-btn.active {
    color: var(--color-accent);
    border-color: var(--color-accent);
    background: var(--color-accent-light, rgba(79,70,229,0.08));
  }

  /* Full-size diagram/chart lightbox — mirrors the .overlay/.close-btn idiom
     used by GroupStatePopup. */
  .overlay {
    position: fixed;
    inset: 0;
    background: rgba(15, 23, 42, 0.6);
    backdrop-filter: blur(4px);
    z-index: 1000;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 40px;
  }

  .lightbox-content {
    position: relative;
    background: var(--glass-bg);
    backdrop-filter: blur(var(--glass-blur));
    border: 1px solid var(--glass-border);
    border-radius: var(--radius-lg, 12px);
    padding: 28px;
    max-width: 92vw;
    max-height: 88vh;
    overflow: auto;
    box-shadow: var(--shadow-lg);
  }

  .close-btn {
    position: absolute;
    top: 10px;
    right: 10px;
    width: 32px;
    height: 32px;
    border: none;
    border-radius: 50%;
    background: var(--color-surface-hover);
    color: var(--color-text-primary);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: background var(--duration-fast) ease, transform var(--duration-fast) var(--ease-spring);
  }

  .close-btn:hover {
    background: var(--color-border);
    transform: scale(1.1) rotate(90deg);
  }

  .lightbox-render {
    display: flex;
    align-items: center;
    justify-content: center;
    min-width: 320px;
    min-height: 240px;
  }

  .lightbox-render :global(svg) {
    max-width: 100%;
    width: 80vw;
    height: auto;
  }

  .lightbox-render :global(canvas) {
    width: 80vw !important;
    height: 70vh !important;
  }
</style>