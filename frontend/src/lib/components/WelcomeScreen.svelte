<script lang="ts">
  interface Props {
    onSuggestionClick: (suggestion: string) => void
  }

  let { onSuggestionClick }: Props = $props()

  const suggestions = [
    {
      title: "What's new in Java 25?",
      description: 'Latest features and changes',
      query: "What's new in Java 25?"
    },
    {
      title: 'What are Records?',
      description: 'Immutable data classes',
      query: 'What are records in Java and when should I use them?'
    },
    {
      title: 'Virtual Threads',
      description: 'Lightweight concurrency',
      query: 'How do virtual threads work in Java?'
    },
    {
      title: 'Pattern Matching',
      description: 'Modern switch expressions',
      query: 'Explain pattern matching for switch in Java'
    }
  ]
</script>

<div class="welcome">
  <div class="welcome-header">
    <h1 class="welcome-title">
      <span class="title-serif">Ask about</span>
      <span class="title-accent">Java</span>
    </h1>
    <p class="welcome-subtitle">
      Get AI-powered answers from the official JDK 25 documentation.
      Ask about APIs, patterns, or best practices.
    </p>
  </div>

  <div class="suggestions">
    <p class="suggestions-label">Try asking about</p>
    <div class="suggestions-grid">
      {#each suggestions as suggestion}
        <button
          type="button"
          class="suggestion-card"
          onclick={() => onSuggestionClick(suggestion.query)}
        >
          <span class="suggestion-title">{suggestion.title}</span>
          <span class="suggestion-desc">{suggestion.description}</span>
          <svg class="suggestion-arrow" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path fill-rule="evenodd" d="M3 10a.75.75 0 0 1 .75-.75h10.638L10.23 5.29a.75.75 0 1 1 1.04-1.08l5.5 5.25a.75.75 0 0 1 0 1.08l-5.5 5.25a.75.75 0 1 1-1.04-1.08l4.158-3.96H3.75A.75.75 0 0 1 3 10Z" clip-rule="evenodd"/>
          </svg>
        </button>
      {/each}
    </div>
  </div>

  <footer class="welcome-footer">
    <p>
      <kbd>Cmd</kbd> + <kbd>K</kbd> to focus input
      <span class="separator">·</span>
      <kbd>Enter</kbd> to send
    </p>
  </footer>
</div>

<style>
  .welcome {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: 60vh;
    gap: var(--space-12);
    animation: fade-in-up var(--duration-slow) var(--ease-out);
  }

  .welcome-header {
    text-align: center;
    max-width: 480px;
  }

  .welcome-title {
    font-size: var(--text-4xl);
    font-weight: 400;
    line-height: var(--leading-tight);
    margin-bottom: var(--space-4);
  }

  .title-serif {
    font-family: var(--font-serif);
    color: var(--color-text-secondary);
  }

  .title-accent {
    font-family: var(--font-sans);
    font-weight: 600;
    color: var(--color-accent);
  }

  .welcome-subtitle {
    font-size: var(--text-lg);
    line-height: var(--leading-relaxed);
    color: var(--color-text-secondary);
  }

  /* Suggestions */
  .suggestions {
    width: 100%;
    max-width: 640px;
  }

  .suggestions-label {
    font-size: var(--text-sm);
    font-weight: 500;
    color: var(--color-text-tertiary);
    text-transform: uppercase;
    letter-spacing: var(--tracking-wider);
    margin-bottom: var(--space-4);
    text-align: center;
  }

  .suggestions-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: var(--space-3);
  }

  .suggestion-card {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-1);
    padding: var(--space-4);
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-lg);
    cursor: pointer;
    text-align: left;
    transition: all var(--duration-fast) var(--ease-out);
    position: relative;
    overflow: hidden;
  }

  .suggestion-card:hover {
    background: var(--color-bg-tertiary);
    border-color: var(--color-border-default);
    transform: translateY(-2px);
  }

  .suggestion-card:hover .suggestion-arrow {
    opacity: 1;
    transform: translateX(0);
  }

  .suggestion-title {
    font-size: var(--text-base);
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .suggestion-desc {
    font-size: var(--text-sm);
    color: var(--color-text-tertiary);
  }

  .suggestion-arrow {
    position: absolute;
    right: var(--space-4);
    top: 50%;
    transform: translateX(-8px) translateY(-50%);
    width: 16px;
    height: 16px;
    color: var(--color-accent);
    opacity: 0;
    transition: all var(--duration-fast) var(--ease-out);
  }

  /* Footer */
  .welcome-footer {
    color: var(--color-text-muted);
    font-size: var(--text-sm);
  }

  .welcome-footer p {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .separator {
    opacity: 0.5;
  }

  kbd {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 24px;
    height: 22px;
    padding: 0 var(--space-2);
    font-family: var(--font-mono);
    font-size: var(--text-xs);
    font-weight: 500;
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-default);
    border-radius: var(--radius-sm);
    box-shadow: 0 1px 0 var(--color-border-default);
  }

  /* Tablet */
  @media (max-width: 768px) {
    .welcome {
      gap: var(--space-10);
      padding: var(--space-4);
    }

    .suggestions-grid {
      gap: var(--space-2);
    }
  }

  /* Mobile */
  @media (max-width: 640px) {
    .welcome {
      gap: var(--space-8);
      min-height: 50vh;
      padding: var(--space-2);
    }

    .welcome-title {
      font-size: var(--text-2xl);
    }

    .welcome-subtitle {
      font-size: var(--text-sm);
    }

    .suggestions {
      width: 100%;
    }

    .suggestions-label {
      font-size: var(--text-xs);
    }

    .suggestions-grid {
      grid-template-columns: 1fr;
      gap: var(--space-2);
    }

    .suggestion-card {
      padding: var(--space-3);
      min-height: 44px; /* Touch target */
    }

    .suggestion-title {
      font-size: var(--text-sm);
    }

    .suggestion-desc {
      font-size: var(--text-xs);
    }

    .welcome-footer {
      display: none;
    }
  }

  /* Small phones */
  @media (max-width: 380px) {
    .welcome-title {
      font-size: var(--text-xl);
    }

    .welcome-subtitle {
      font-size: var(--text-xs);
    }

    .suggestion-card {
      padding: var(--space-2) var(--space-3);
    }
  }
</style>
