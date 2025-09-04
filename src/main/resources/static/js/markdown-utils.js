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
        const segment = tail.slice(0, relEnd).replace(/^java\s*/i, '');
        const fenced = '```java\n' + segment.trimEnd() + '\n```';
        t = t.slice(0, start) + fenced + t.slice(start + segment.length);
        changed = true;
        searchFrom = start + fenced.length;
      }
    }
    return changed ? t : text;
  }

  // Normalize inline ordered lists (1.text / 1. text ...) into true lists by
  // inserting newlines before list markers. Parser-style, conservative rules:
  // - A list item token is (\d{1,3}) '.' (space|letter)
  // - Preceded by start or whitespace
  // - Not part of a decimal like 3.14 (next char must not be digit)
  // - Only commits when two or more items are found within 600 chars
  function normalizeInlineOrderedLists(text){
    if (!text) return '';
    // Fast path: if we already have multiple newlines with numbers, skip
    if (/^\s*\d+\.\s/m.test(text)) return text;
    const chars = Array.from(text);
    let i = 0;
    const positions = [];
    while (i < chars.length) {
      // find digits
      let j = i;
      let numStart = -1;
      let num = 0;
      while (j < chars.length && chars[j] >= '0' && chars[j] <= '9') {
        if (numStart === -1) numStart = j;
        num = num*10 + (chars[j].charCodeAt(0) - 48);
        j++;
      }
      if (numStart !== -1 && j < chars.length && chars[j] === '.') {
        const prev = numStart > 0 ? chars[numStart-1] : '\n';
        const next = (j+1) < chars.length ? chars[j+1] : '\n';
        const prevOk = /\s|[\(\[]/.test(prev);
        const nextOk = /\s|[A-Za-z]/.test(next); // avoid 3.14 / 1.2.3
        const notDecimal = !(next >= '0' && next <= '9');
        if (prevOk && nextOk && notDecimal) {
          positions.push(numStart);
        }
        i = j+1;
        continue;
      }
      i = (numStart !== -1 ? numStart+1 : j+1);
    }
    if (positions.length < 2) return text; // need at least two items
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

  function shouldImmediateFlush(fullText){
    if (!fullText) return false;
    const tail4 = fullText.slice(-4);
    const tail2 = fullText.slice(-2);
    return /[.!?][\"')]*\s$/.test(tail4) || /\n\n/.test(tail2) || fullText.endsWith('```\n');
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
    } catch(_){}
  }

  function safeHighlightUnder(el){
    try { if (global.Prism && typeof Prism.highlightAllUnder === 'function') Prism.highlightAllUnder(el); } catch(_) {}
  }

  /**
   * Creates a citation pill element with proper styling, icons, and link handling.
   * Extracted from chat.html to ensure consistent citation rendering across views.
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
    
    // Determine label
    let label = citation.title || 'Source';
    if (!citation.title && isHttpLink) {
      try {
        label = new URL(href).hostname;
      } catch (_) {
        // Keep default label on URL parse error
      }
    }
    
    // Icon SVGs (consistent with chat.html implementation)
    const iconExternal = `<svg class="citation-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path><polyline points="15 3 21 3 21 9"></polyline><line x1="10" y1="14" x2="21" y2="3"></line></svg>`;
    const iconPdf = `<svg class="citation-icon" viewBox="0 0 24 24" fill="currentColor"><path d="M6 2h9l5 5v15a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zm8 1.5V8h4.5"/><path d="M7 15h2.5a2 2 0 0 0 0-4H7v4zm5-4h1v4h-1a2 2 0 0 1-2-2v0a2 2 0 0 1 2-2zm5 0h-2v4h2"/></svg>`;
    
    // Build pill content with structured HTML
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

  global.MU = {
    normalizeOpeningFences: normalizeOpeningFences,
    promoteLikelyJavaBlocks: promoteLikelyJavaBlocks,
    normalizeInlineOrderedLists: normalizeInlineOrderedLists,
    shouldImmediateFlush: shouldImmediateFlush,
    upgradeCodeBlocks: upgradeCodeBlocks,
    attachCodeCopyButtons: attachCodeCopyButtons,
    safeHighlightUnder: safeHighlightUnder,
    createCitationPill: createCitationPill,
    createCitationsRow: createCitationsRow,
  };
})(window);
