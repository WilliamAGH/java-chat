<script lang="ts">
  import type { ChatMessage } from '../services/chat'
  import { renderMarkdown } from '../services/markdown'

  interface Props {
    message: ChatMessage
    index: number
    isStreaming?: boolean
  }

  let { message, index, isStreaming = false }: Props = $props()

  let renderedContent = $state('')
  let contentEl: HTMLElement | null = $state(null)

  // Render markdown when content changes
  $effect(() => {
    if (message.role === 'assistant' && message.content) {
      renderMarkdown(message.content).then(html => {
        renderedContent = html
      })
    }
  })

  // Highlight code blocks after render - use core + java only for smaller bundle
  $effect(() => {
    if (renderedContent && contentEl) {
      Promise.all([
        import('highlight.js/lib/core'),
        import('highlight.js/lib/languages/java'),
        import('highlight.js/lib/languages/xml'),
        import('highlight.js/lib/languages/json'),
        import('highlight.js/lib/languages/bash')
      ]).then(([hljs, java, xml, json, bash]) => {
        hljs.default.registerLanguage('java', java.default)
        hljs.default.registerLanguage('xml', xml.default)
        hljs.default.registerLanguage('json', json.default)
        hljs.default.registerLanguage('bash', bash.default)
        contentEl?.querySelectorAll('pre code').forEach((block) => {
          hljs.default.highlightElement(block as HTMLElement)
        })
      })
    }
  })

  function copyToClipboard(text: string) {
    navigator.clipboard.writeText(text)
  }

  // Compute animation delay reactively
  let animationDelay = $derived(`${Math.min(index * 50, 200)}ms`)
</script>

<div
  class="message"
  class:user={message.role === 'user'}
  class:assistant={message.role === 'assistant'}
  class:error={message.isError}
  class:streaming={isStreaming}
  style:animation-delay={animationDelay}
>
  {#if message.role === 'assistant'}
    <div class="avatar">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <path d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456ZM16.894 20.567 16.5 21.75l-.394-1.183a2.25 2.25 0 0 0-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 0 0 1.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 0 0 1.423 1.423l1.183.394-1.183.394a2.25 2.25 0 0 0-1.423 1.423Z" />
      </svg>
    </div>
  {/if}

  <div class="bubble">
    {#if message.role === 'user'}
      <p class="user-text">{message.content}</p>
    {:else}
      <div class="assistant-content" bind:this={contentEl}>
        {#if renderedContent}
          {@html renderedContent}
        {:else}
          <p>{message.content}</p>
        {/if}
        {#if isStreaming}
          <span class="cursor"></span>
        {/if}
      </div>
    {/if}

    <div class="bubble-actions">
      <button
        type="button"
        class="action-btn"
        onclick={() => copyToClipboard(message.content)}
        title="Copy message"
      >
        <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path d="M7 3.5A1.5 1.5 0 0 1 8.5 2h3.879a1.5 1.5 0 0 1 1.06.44l3.122 3.12A1.5 1.5 0 0 1 17 6.622V12.5a1.5 1.5 0 0 1-1.5 1.5h-1v-3.379a3 3 0 0 0-.879-2.121L10.5 5.379A3 3 0 0 0 8.379 4.5H7v-1Z"/>
          <path d="M4.5 6A1.5 1.5 0 0 0 3 7.5v9A1.5 1.5 0 0 0 4.5 18h7a1.5 1.5 0 0 0 1.5-1.5v-5.879a1.5 1.5 0 0 0-.44-1.06L9.44 6.439A1.5 1.5 0 0 0 8.378 6H4.5Z"/>
        </svg>
      </button>
    </div>
  </div>
</div>

<style>
  .message {
    display: flex;
    gap: var(--space-3);
    animation: fade-in-up var(--duration-normal) var(--ease-out) backwards;
  }

  .message.user {
    justify-content: flex-end;
  }

  .message.assistant {
    justify-content: flex-start;
  }

  /* Avatar */
  .avatar {
    flex-shrink: 0;
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--color-accent-subtle);
    border: 1px solid var(--color-accent-muted);
    border-radius: var(--radius-md);
    color: var(--color-accent);
  }

  .avatar svg {
    width: 18px;
    height: 18px;
  }

  /* Bubble */
  .bubble {
    position: relative;
    max-width: 85%;
    padding: var(--space-4);
    border-radius: var(--radius-xl);
    transition: background-color var(--duration-fast) var(--ease-out);
  }

  .message.user .bubble {
    background: var(--color-accent);
    color: white;
    border-bottom-right-radius: var(--radius-sm);
  }

  .message.assistant .bubble {
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-subtle);
    border-bottom-left-radius: var(--radius-sm);
  }

  .message.error .bubble {
    background: rgba(196, 93, 93, 0.1);
    border-color: rgba(196, 93, 93, 0.2);
    color: var(--color-error);
  }

  /* Shared text wrapping for long unbroken strings (URLs, code identifiers) */
  .user-text,
  .assistant-content {
    overflow-wrap: break-word;
    word-break: break-word;
  }

  /* User text */
  .user-text {
    font-size: var(--text-base);
    line-height: var(--leading-relaxed);
    margin: 0;
  }

  /* Assistant content - prose styles */
  .assistant-content {
    font-size: var(--text-base);
    line-height: var(--leading-relaxed);
    color: var(--color-text-primary);
  }

  .assistant-content :global(p) {
    margin: 0 0 var(--space-4);
  }

  .assistant-content :global(p:last-child) {
    margin-bottom: 0;
  }

  .assistant-content :global(h1),
  .assistant-content :global(h2),
  .assistant-content :global(h3),
  .assistant-content :global(h4) {
    font-family: var(--font-serif);
    font-weight: 500;
    margin: var(--space-6) 0 var(--space-3);
    letter-spacing: var(--tracking-tight);
  }

  .assistant-content :global(h1:first-child),
  .assistant-content :global(h2:first-child),
  .assistant-content :global(h3:first-child) {
    margin-top: 0;
  }

  .assistant-content :global(h2) {
    font-size: var(--text-xl);
  }

  .assistant-content :global(h3) {
    font-size: var(--text-lg);
  }

  .assistant-content :global(ul),
  .assistant-content :global(ol) {
    margin: 0 0 var(--space-4);
    padding-left: var(--space-6);
  }

  .assistant-content :global(li) {
    margin-bottom: var(--space-2);
  }

  .assistant-content :global(li:last-child) {
    margin-bottom: 0;
  }

  .assistant-content :global(strong) {
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .assistant-content :global(a) {
    color: var(--color-accent);
    text-decoration: underline;
    text-underline-offset: 2px;
  }

  .assistant-content :global(a:hover) {
    text-decoration-thickness: 2px;
  }

  .assistant-content :global(blockquote) {
    margin: var(--space-4) 0;
    padding: var(--space-3) var(--space-4);
    border-left: 3px solid var(--color-accent);
    background: var(--color-surface-subtle);
    border-radius: 0 var(--radius-md) var(--radius-md) 0;
    font-style: italic;
    color: var(--color-text-secondary);
  }

  /* Code blocks in assistant messages */
  .assistant-content :global(pre) {
    margin: var(--space-4) 0;
    padding: var(--space-4);
    background: var(--color-bg-primary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-lg);
    overflow-x: auto;
    font-size: var(--text-sm);
  }

  .assistant-content :global(pre code) {
    background: none;
    padding: 0;
    border: none;
    font-size: inherit;
    color: var(--color-text-primary);
  }

  /* Streaming cursor */
  .cursor {
    display: inline-block;
    width: 2px;
    height: 1.2em;
    background: var(--color-accent);
    margin-left: 2px;
    vertical-align: text-bottom;
    animation: typing-cursor 0.8s ease-in-out infinite;
  }

  @keyframes typing-cursor {
    0%, 100% { opacity: 1; }
    50% { opacity: 0; }
  }

  /* Actions */
  .bubble-actions {
    position: absolute;
    bottom: var(--space-2);
    right: var(--space-2);
    opacity: 0;
    transition: opacity var(--duration-fast) var(--ease-out);
  }

  .bubble:hover .bubble-actions {
    opacity: 1;
  }

  .action-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    background: var(--color-bg-elevated);
    border: 1px solid var(--color-border-default);
    border-radius: var(--radius-md);
    color: var(--color-text-tertiary);
    cursor: pointer;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .action-btn:hover {
    color: var(--color-text-primary);
    background: var(--color-bg-hover);
  }

  .action-btn svg {
    width: 14px;
    height: 14px;
  }

  .message.user .action-btn {
    background: rgba(255, 255, 255, 0.15);
    border-color: rgba(255, 255, 255, 0.2);
    color: rgba(255, 255, 255, 0.7);
  }

  .message.user .action-btn:hover {
    background: rgba(255, 255, 255, 0.25);
    color: white;
  }

  /* Tablet */
  @media (max-width: 768px) {
    .bubble {
      max-width: 88%;
    }
  }

  /* Mobile */
  @media (max-width: 640px) {
    .message {
      gap: var(--space-2);
    }

    .bubble {
      max-width: 92%;
      padding: var(--space-3);
    }

    .avatar {
      width: 28px;
      height: 28px;
    }

    .avatar svg {
      width: 16px;
      height: 16px;
    }

    .bubble-actions {
      opacity: 1;
    }

    .assistant-content {
      font-size: var(--text-sm);
    }

    .assistant-content :global(pre) {
      padding: var(--space-3);
      font-size: var(--text-xs);
      margin: var(--space-3) calc(-1 * var(--space-3));
      border-radius: 0;
      border-left: none;
      border-right: none;
    }

    .assistant-content :global(code:not(pre code)) {
      font-size: 0.8em;
    }
  }

  /* Small phones */
  @media (max-width: 380px) {
    .bubble {
      max-width: 95%;
      padding: var(--space-2) var(--space-3);
    }

    .avatar {
      width: 24px;
      height: 24px;
    }

    .avatar svg {
      width: 14px;
      height: 14px;
    }

    .user-text {
      font-size: var(--text-sm);
    }
  }
</style>
