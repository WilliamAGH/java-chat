// Minimal, DRY utilities for consistent code block handling across views
// Idempotent by design — safe to call multiple times.
(function(global){
  function normalizeOpeningFences(text){
    if (!text) return '';
    return text
      .replace(/```([A-Za-z0-9_-]+)[ \t]*([^\n])/g, '```$1\n$2')
      .replace(/```[ \t]*([^\n])/g, '```\n$1');
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

  global.MU = {
    normalizeOpeningFences: normalizeOpeningFences,
    shouldImmediateFlush: shouldImmediateFlush,
    upgradeCodeBlocks: upgradeCodeBlocks,
    attachCodeCopyButtons: attachCodeCopyButtons,
    safeHighlightUnder: safeHighlightUnder,
  };
})(window);

