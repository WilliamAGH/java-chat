// Minimal, DRY utilities for consistent code block handling across views
// Idempotent by design — safe to call multiple times.
(function(global){
  function normalizeOpeningFences(text){
    if (!text) return '';
    return text
      .replace(/```([A-Za-z0-9_-]+)[ \t]*([^\n])/g, '```$1\n$2')
      .replace(/```[ \t]*([^\n])/g, '```\n$1');
  }

  // Conservative promotion of likely Java snippets into fenced blocks (fallback only).
  // Idempotent: skips when backticks already exist near the target.
  function promoteLikelyJavaBlocks(text){
    if (!text || text.indexOf('```') !== -1) return text;
    const cues = /(example\s*(code)?|here(?:'| i)s\s*a\s*(?:simple\s*)?example|for\s+example)/i;
    const triggers = [
      /java\s*(?:import|public|class|package|record|enum)\b/i,
      /\bimport\s+java\b/i,
      /\bpublic\s+class\b/i,
      /\bclass\s+\w+\s*\{/i,
      /java\s*:\s*\S/i,     // "java: ..."
      /java\s*\/\//i        // "java// ..."
    ];
    let t = text;
    let changed = false;
    for (const trig of triggers) {
      let searchFrom = 0;
      while (searchFrom < t.length) {
        const m = trig.exec(t.slice(searchFrom));
        if (!m) break;
        const start = searchFrom + m.index;
        // Skip if already inside fences
        const lastOpen = t.lastIndexOf('```', start);
        const nextOpen = t.indexOf('```', start);
        if (lastOpen !== -1 && (nextOpen === -1 || lastOpen > start || (lastOpen < start && nextOpen > start))) {
          searchFrom = start + m[0].length;
          continue;
        }
        // Require a cue within 120 chars before
        const preStart = Math.max(0, start - 120);
        const before = t.slice(preStart, start);
        if (!cues.test(before)) {
          searchFrom = start + m[0].length;
          continue;
        }
        // Find a safe end: next blank line or enrichment or end
        const tail = t.slice(start);
        const endMatch = /(\n\s*\n|ZZENRICHZ|\{\{|^###|\n#{2,}|$)/m.exec(tail);
        const relEnd = endMatch ? endMatch.index : tail.length;
        if (relEnd < 8) { // too short to be useful
          searchFrom = start + m[0].length;
          continue;
        }
        const original = tail.slice(0, relEnd);
        const segment = original.replace(/^java\s*/i, '');
        const fenced = '```java\n' + segment.trimEnd() + '\n```';
        // Replace the original span (before relEnd) with a fenced block.
        t = t.slice(0, start) + fenced + t.slice(start + relEnd);
        changed = true;
        searchFrom = start + fenced.length;
      }
    }
    return changed ? t : text;
  }

  // Normalize inline lists (numbers, bullets, lettered) into true lists by
  // inserting newlines before list markers. Parser-style, conservative rules.
  function normalizeInlineOrderedLists(text){
    if (!text) return '';
    const chars = Array.from(text);
    let i = 0;
    const positions = [];
    while (i < chars.length) {
      let j = i;
      // bullets: - * +
      if (chars[j] === '-' || chars[j] === '*' || chars[j] === '+') {
        const prev = j > 0 ? chars[j-1] : '\n';
        const next = (j+1) < chars.length ? chars[j+1] : '\n';
        const atStart = j === 0 || prev === '\n';
        if (!atStart && /\s/.test(prev) && /\s/.test(next)) positions.push(j);
        i = j+2; continue;
      }
      // numbers: 1. or 1)
      if (/[0-9]/.test(chars[j])) {
        const start = j; j++;
        while (j < chars.length && /[0-9]/.test(chars[j])) j++;
        if (j < chars.length && (chars[j] === '.' || chars[j] === ')')) {
          const prev = start > 0 ? chars[start-1] : '\n';
          const next = (j+1) < chars.length ? chars[j+1] : '\n';
          const atStart = start === 0 || prev === '\n';
          const prevOk = /\s/.test(prev) || prev === '(' || prev === '[';
          const nextOk = /\s/.test(next) || /[A-Za-z]/.test(next);
          const notDecimal = !(/[0-9]/.test(next));
          if (!atStart && prevOk && nextOk && notDecimal) positions.push(start);
          i = j+1; continue;
        }
      }
      // lettered: a) A) a. A.
      if (/[A-Za-z]/.test(chars[j]) && (j+1) < chars.length && (chars[j+1] === ')' || chars[j+1] === '.')) {
        const prev = j > 0 ? chars[j-1] : '\n';
        const next = (j+2) < chars.length ? chars[j+2] : '\n';
        const atStart = j === 0 || prev === '\n';
        const prevOk = /\s/.test(prev) || prev === '(' || prev === '[';
        const nextOk = /\s/.test(next) || /[A-Za-z]/.test(next);
        if (!atStart && prevOk && nextOk) positions.push(j);
        i = j+2; continue;
      }
      i = j+1;
    }
    if (positions.length === 0) return text;
    // Commit: insert a newline before each detected item if not already at line start
    let out = '';
    let last = 0;
    for (const pos of positions) {
      // If already at line start, skip
      const atStart = pos === 0 || text.slice(Math.max(0,pos-1), pos) === '\n';
      if (!atStart) {
        out += text.slice(last, pos) + '\n';
        last = pos;
      }
    }
    out += text.slice(last);
    return out;
  }

  // If a line is a marker only ("1.", "-", "a)"), merge the next non-empty line as content
  function hoistMarkerOnlyLines(text){
    if (!text) return '';
    const lines = text.split(/\r?\n/);
    const out = [];
    for (let i=0;i<lines.length;i++){
      const ln = lines[i];
      const t = ln.trim();
      if (/^(?:\d+[.)]|[A-Za-z][.)]|[-*+])\s*$/.test(t) && i+1 < lines.length && lines[i+1].trim() !== ''){
        out.push(t + ' ' + lines[i+1].trim()); i++;
      } else { out.push(ln); }
    }
    return out.join('\n');
  }

  function shouldImmediateFlush(fullText){
    if (!fullText) return false;
    const tail4 = fullText.slice(-4);
    const tail2 = fullText.slice(-2);
    return /[.!?]["')]*\s$/.test(tail4) || /\n\n/.test(tail2) || fullText.endsWith('```\n');
  }

  function upgradeCodeBlocks(container){
    if (!container || typeof container.querySelectorAll !== 'function') return;
    // Multi-line inline <code> -> <pre><code>
    const codeNodes = container.querySelectorAll('code');
    codeNodes.forEach(node => {
      if (node.closest('pre') || node.closest('.inline-enrichment')) return;
      const text = node.textContent || '';
      if (text.indexOf('\n') === -1) return;
      let body = text.trim();
      body = body.replace(/^```?\s*([A-Za-z0-9_-]+)?\s*\n/, '');
      const pre = document.createElement('pre');
      const code = document.createElement('code');
      code.className = 'language-java';
      code.textContent = body;
      pre.appendChild(code);
      node.replaceWith(pre);
    });

    // Wrap bare <pre> in a presentational container (idempotent)
    const preNodes = container.querySelectorAll('pre');
    preNodes.forEach(pre => {
      if (pre.closest('.inline-enrichment')) return;
      const wrapper = document.createElement('div');
      wrapper.className = 'inline-enrichment example';
      const header = document.createElement('div');
      header.className = 'inline-enrichment-header';
      header.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg><span>Code Example</span>';
      const bodyDiv = document.createElement('div');
      bodyDiv.className = 'enrichment-text';
      bodyDiv.appendChild(pre.cloneNode(true));
      wrapper.appendChild(header);
      wrapper.appendChild(bodyDiv);
      pre.replaceWith(wrapper);
    });
  }

  function attachCodeCopyButtons(container){
    try {
      const blocks = container.querySelectorAll('pre');
      blocks.forEach(pre => {
        if (pre.querySelector('.code-copy-btn')) return; // idempotent
        const btn = document.createElement('button');
        btn.className = 'code-copy-btn';
        btn.setAttribute('aria-label','Copy code');
        btn.title = 'Copy code';
        btn.innerHTML = '<svg width="16" height="16" viewbox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>';
        btn.addEventListener('click', (e) => {
          e.stopPropagation();
          const codeEl = pre.querySelector('code');
          const text = codeEl ? codeEl.innerText : pre.innerText;
          navigator.clipboard.writeText(text).then(() => {
            btn.classList.add('copied');
            const orig = btn.innerHTML;
            btn.innerHTML = '<svg width="16" height="16" viewbox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg>';
            setTimeout(() => { btn.classList.remove('copied'); btn.innerHTML = orig; }, 1500);
          });
        });
        pre.appendChild(btn);
      });
    } catch {}
  }

  function safeHighlightUnder(el){
    try { if (global.Prism && typeof Prism.highlightAllUnder === 'function') Prism.highlightAllUnder(el); } catch {}
  }

  // Shared enrichment rendering
  const ENRICH_ICONS = {
    hint: '<svg viewbox="0 0 24 24" fill="currentColor"><path d="M12 2a7 7 0 0 0-7 7c0 2.59 1.47 4.84 3.63 6.02L9 18h6l.37-2.98A7.01 7.01 0 0 0 19 9a7 7 0 0 0-7-7zm-3 19h6v1H9v-1z"/></svg>',
    background: '<svg viewbox="0 0 24 24" fill="currentColor"><path d="M4 6h16v2H4zM4 10h16v2H4zM4 14h16v2H4z"/></svg>',
    reminder: '<svg viewbox="0 0 24 24" fill="currentColor"><path d="M12 22a2 2 0 0 0 2-2H10a2 2 0 0 0 2 2zm6-6v-5a6 6 0 0 0-4-5.65V4a2 2 0 0 0-4 0v1.35A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2z"/></svg>',
    warning: '<svg viewbox="0 0 24 24" fill="currentColor"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2V7h2v7z"/></svg>',
    example: '<svg viewbox="0 0 24 24" fill="currentColor"><path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z"/></svg>'
  };

  function createEnrichmentBlock(type, title, items){
    const card = document.createElement('div');
    card.className = `inline-enrichment ${type}`;
    const header = document.createElement('div');
    header.className = 'inline-enrichment-header';
    header.innerHTML = `${ENRICH_ICONS[type] || ''}<span>${title}</span>`;
    const body = document.createElement('div');
    body.className = 'enrichment-text';
    const appendParagraph = (txt) => { const p = document.createElement('p'); p.textContent = txt || ''; body.appendChild(p); };
    if (Array.isArray(items)) { items.filter(Boolean).forEach((txt) => appendParagraph(txt)); }
    else if (typeof items === 'string') { appendParagraph(items); }
    card.appendChild(header);
    card.appendChild(body);
    return card;
  }

  // Replace inline markers with unified enrichment blocks
  function applyInlineEnrichments(text){
    if (!text) return '';
    let t = String(text);
    t = t.replace(/\{\{hint:([\s\S]*?)\}\}/g, (m, c) => {
      const el = createEnrichmentBlock('hint', 'Helpful Hint', [c.trim()]);
      return `\n${el.outerHTML}\n`;
    });
    t = t.replace(/\{\{reminder:([\s\S]*?)\}\}/g, (m, c) => {
      const el = createEnrichmentBlock('reminder', 'Important Reminder', [c.trim()]);
      return `\n${el.outerHTML}\n`;
    });
    t = t.replace(/\{\{background:([\s\S]*?)\}\}/g, (m, c) => {
      const el = createEnrichmentBlock('background', 'Background Context', [c.trim()]);
      return `\n${el.outerHTML}\n`;
    });
    t = t.replace(/\{\{warning:([\s\S]*?)\}\}/g, (m, c) => {
      const el = createEnrichmentBlock('warning', 'Warning', [c.trim()]);
      return `\n${el.outerHTML}\n`;
    });
    return t;
  }

  /**
   * Creates a citation pill element with proper styling, icons, and link handling.
   * Extracted from chat.html to ensure consistent citation rendering across views.
   * Fully restores the sophisticated URL handling logic from the original inline code.
   * 
   * @param {Object} citation - Citation object with url, title properties
   * @param {number} index - Zero-based index for numbering (will display as index + 1)
   * @returns {HTMLElement} - Configured citation pill element (a or div)
   */
  function createCitationPill(citation, index) {
    const href = citation.url || '';
    const isHttpLink = href.startsWith('http://') || href.startsWith('https://');
    const isLocalLink = href.startsWith('/');
    const isPdf = href.toLowerCase().endsWith('.pdf');
    const isLink = !!href && (isHttpLink || isLocalLink);
    
    // Create appropriate element type
    const pill = document.createElement(isLink ? 'a' : 'div');
    pill.className = 'citation-pill' + (isPdf ? ' citation-pill-pdf' : '');
    
    // Configure link properties
    if (isLink) {
      pill.href = href;
      pill.target = '_blank';
      pill.rel = 'noopener noreferrer';
    }
    
    // RESTORED: Sophisticated label logic from original inline code
    let label = citation.title || 'Source';
    if (!citation.title && isHttpLink) {
      try {
        // Extract hostname for external links when no title is provided
        label = new URL(href).hostname;
      } catch {
        // Keep default label on URL parse error
      }
    }
    // Replace :: separator with | for cleaner appearance
    label = label.replace(/::/g, '|');
    
    // RESTORED: Original SVG icons with exact same markup as inline code
    const iconExternal = `<svg class="citation-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path><polyline points="15 3 21 3 21 9"></polyline><line x1="10" y1="14" x2="21" y2="3"></line></svg>`;
    const iconPdf = `<svg class="citation-icon" viewBox="0 0 24 24" fill="currentColor"><path d="M6 2h9l5 5v15a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zm8 1.5V8h4.5"/><path d="M7 15h2.5a2 2 0 0 0 0-4H7v4zm5-4h1v4h-1a2 2 0 0 1-2-2v0a2 2 0 0 1 2-2zm5 0h-2v4h2"/></svg>`;
    
    // RESTORED: Exact same HTML structure as original inline code
    pill.innerHTML = `<span class="citation-number">${index + 1}</span><span class="citation-label">${label}${isPdf ? ' (PDF)' : ''}</span>${isLink ? (isPdf ? iconPdf : iconExternal) : ''}`;
    
    return pill;
  }

  /**
   * Creates a citations row container with citation pills.
   * Safe to call multiple times - will not duplicate citations.
   * 
   * @param {Array} citations - Array of citation objects
   * @param {number} maxCount - Maximum number of citations to display (default: 5)
   * @returns {HTMLElement} - Citations row container element
   */
  function createCitationsRow(citations, maxCount = 5) {
    const citationsRow = document.createElement('div');
    citationsRow.className = 'citations-row';
    
    if (!citations || citations.length === 0) {
      return citationsRow; // Return empty container
    }
    
    citations.slice(0, maxCount).forEach((citation, index) => {
      const pill = createCitationPill(citation, index);
      citationsRow.appendChild(pill);
    });
    
    return citationsRow;
  }

  // Initialize metrics object for potential future use
  const _metrics = {};

  global.MU = {
    metrics: _metrics,
    normalizeOpeningFences: normalizeOpeningFences,
    promoteLikelyJavaBlocks: promoteLikelyJavaBlocks,
    normalizeInlineOrderedLists: normalizeInlineOrderedLists,
    hoistMarkerOnlyLines: hoistMarkerOnlyLines,
    shouldImmediateFlush: shouldImmediateFlush,
    upgradeCodeBlocks: upgradeCodeBlocks,
    attachCodeCopyButtons: attachCodeCopyButtons,
    safeHighlightUnder: safeHighlightUnder,
    createEnrichmentBlock: createEnrichmentBlock,
    applyInlineEnrichments: applyInlineEnrichments,
    createCitationPill: createCitationPill,
    createCitationsRow: createCitationsRow,
    setDebug: (v) => { try { global.MU_DEBUG = !!v; } catch {} },
  };

  // Tiny list debug: auto-enable in dev contexts or via URL/localStorage
  try {
    const params = new URLSearchParams(global.location ? global.location.search : '');
    const fromQuery = params.get('debug') === 'list';
    const fromStorage = (global.localStorage && global.localStorage.getItem('mu_debug') === '1');
    const isLocal = (global.location && (/localhost|127\.0\.0\.1/.test(global.location.hostname)));
    if (fromQuery || fromStorage || isLocal) global.MU_DEBUG = true;
  } catch {}
})(window);
