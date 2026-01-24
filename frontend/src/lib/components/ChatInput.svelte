<script lang="ts">
  import { onMount } from 'svelte'

  interface Props {
    onSend: (message: string) => void
    disabled?: boolean
  }

  let { onSend, disabled = false }: Props = $props()

  let inputValue = $state('')
  let inputEl: HTMLTextAreaElement | null = $state(null)

  function handleSubmit() {
    if (!inputValue.trim() || disabled) return
    onSend(inputValue.trim())
    inputValue = ''
    // Reset height after clearing
    if (inputEl) {
      inputEl.style.height = 'auto'
    }
  }

  function handleKeyDown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  function autoResize() {
    if (!inputEl) return
    inputEl.style.height = 'auto'
    inputEl.style.height = `${Math.min(inputEl.scrollHeight, 200)}px`
  }

  // Global keyboard shortcut
  onMount(() => {
    function handleGlobalKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        inputEl?.focus()
        inputEl?.select()
      }
      if (e.key === 'Escape' && document.activeElement === inputEl) {
        inputValue = ''
        if (inputEl) inputEl.style.height = 'auto'
      }
    }

    document.addEventListener('keydown', handleGlobalKeyDown)
    inputEl?.focus()

    return () => {
      document.removeEventListener('keydown', handleGlobalKeyDown)
    }
  })
</script>

<div class="input-area">
  <div class="input-container">
    <div class="input-wrapper">
      <textarea
        bind:this={inputEl}
        bind:value={inputValue}
        oninput={autoResize}
        onkeydown={handleKeyDown}
        placeholder="Ask about Java..."
        rows="1"
        {disabled}
        class="input-field"
        aria-label="Message input"
      ></textarea>

      <button
        type="button"
        class="send-btn"
        onclick={handleSubmit}
        disabled={disabled || !inputValue.trim()}
        aria-label="Send message"
      >
        {#if disabled}
          <svg class="spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <circle cx="12" cy="12" r="10" stroke-dasharray="60" stroke-dashoffset="20" />
          </svg>
        {:else}
          <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path d="M3.105 2.288a.75.75 0 0 0-.826.95l1.414 4.926A1.5 1.5 0 0 0 5.135 9.25h6.115a.75.75 0 0 1 0 1.5H5.135a1.5 1.5 0 0 0-1.442 1.086l-1.414 4.926a.75.75 0 0 0 .826.95 28.897 28.897 0 0 0 15.293-7.155.75.75 0 0 0 0-1.114A28.897 28.897 0 0 0 3.105 2.288Z"/>
          </svg>
        {/if}
      </button>
    </div>

    <p class="input-hint">
      <span class="hint-text">Press <kbd>Enter</kbd> to send, <kbd>Shift + Enter</kbd> for new line</span>
    </p>
  </div>
</div>

<style>
  .input-area {
    background: var(--color-bg-primary);
    border-top: 1px solid var(--color-border-subtle);
    padding: var(--space-4) var(--space-6);
  }

  .input-container {
    max-width: 800px;
    margin: 0 auto;
  }

  .input-wrapper {
    display: flex;
    align-items: flex-end;
    gap: var(--space-3);
    padding: var(--space-3);
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-xl);
    transition: all var(--duration-fast) var(--ease-out);
  }

  .input-wrapper:focus-within {
    border-color: var(--color-accent);
    box-shadow: 0 0 0 3px var(--color-accent-subtle);
  }

  .input-field {
    flex: 1;
    padding: var(--space-2) var(--space-1);
    font-family: var(--font-sans);
    font-size: var(--text-base);
    line-height: var(--leading-normal);
    color: var(--color-text-primary);
    background: transparent;
    border: none;
    outline: none;
    resize: none;
    min-height: 24px;
    max-height: 200px;
  }

  .input-field::placeholder {
    color: var(--color-text-muted);
  }

  .input-field:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .send-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    background: var(--color-accent);
    border: none;
    border-radius: var(--radius-lg);
    color: white;
    cursor: pointer;
    transition: all var(--duration-fast) var(--ease-out);
    flex-shrink: 0;
  }

  .send-btn:hover:not(:disabled) {
    background: var(--color-accent-hover);
    transform: scale(1.05);
  }

  .send-btn:active:not(:disabled) {
    transform: scale(0.98);
  }

  .send-btn:disabled {
    background: var(--color-bg-elevated);
    color: var(--color-text-muted);
    cursor: not-allowed;
  }

  .send-btn svg {
    width: 20px;
    height: 20px;
  }

  .spinner {
    animation: spin 1s linear infinite;
  }

  @keyframes spin {
    from { transform: rotate(0deg); }
    to { transform: rotate(360deg); }
  }

  /* Hint */
  .input-hint {
    margin-top: var(--space-2);
    text-align: center;
  }

  .hint-text {
    font-size: var(--text-xs);
    color: var(--color-text-muted);
  }

  .hint-text kbd {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 20px;
    height: 18px;
    padding: 0 var(--space-1);
    font-family: var(--font-mono);
    font-size: 10px;
    font-weight: 500;
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-subtle);
    border-radius: 3px;
    box-shadow: 0 1px 0 var(--color-border-subtle);
  }

  /* Tablet */
  @media (max-width: 768px) {
    .input-area {
      padding: var(--space-3) var(--space-4);
    }
  }

  /* Mobile */
  @media (max-width: 640px) {
    .input-area {
      padding: var(--space-2) var(--space-3);
      padding-bottom: max(var(--space-3), env(safe-area-inset-bottom));
    }

    .input-wrapper {
      padding: var(--space-2);
      gap: var(--space-2);
    }

    .input-field {
      font-size: 16px; /* Prevents iOS zoom on focus */
    }

    .input-hint {
      display: none;
    }

    .send-btn {
      width: 44px;
      height: 44px;
      min-width: 44px; /* Touch target */
    }

    .send-btn svg {
      width: 20px;
      height: 20px;
    }
  }

  /* Small phones */
  @media (max-width: 380px) {
    .input-area {
      padding: var(--space-2);
      padding-bottom: max(var(--space-2), env(safe-area-inset-bottom));
    }

    .input-wrapper {
      border-radius: var(--radius-lg);
    }

    .send-btn {
      width: 40px;
      height: 40px;
      min-width: 40px;
      border-radius: var(--radius-md);
    }
  }
</style>
