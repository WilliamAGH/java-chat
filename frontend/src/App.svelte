<script lang="ts">
  import Header from './lib/components/Header.svelte'
  import ChatView from './lib/components/ChatView.svelte'

  let currentView = $state<'chat' | 'learn'>('chat')
</script>

<div class="app-shell">
  <Header bind:currentView />

  <main class="main-content">
    {#if currentView === 'chat'}
      <ChatView />
    {:else}
      <div class="coming-soon">
        <span class="coming-soon-icon">📚</span>
        <h2>Guided Learning</h2>
        <p>Structured Java tutorials coming soon.</p>
      </div>
    {/if}
  </main>
</div>

<style>
  .app-shell {
    display: flex;
    flex-direction: column;
    min-height: 100vh;
    min-height: 100dvh;
  }

  .main-content {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    padding-top: 57px; /* Height of fixed header */
  }

  .coming-soon {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-4);
    color: var(--color-text-secondary);
    text-align: center;
  }

  .coming-soon-icon {
    font-size: 3rem;
    opacity: 0.6;
  }

  .coming-soon h2 {
    font-family: var(--font-serif);
    font-size: var(--text-2xl);
    font-weight: 500;
    color: var(--color-text-primary);
    letter-spacing: var(--tracking-tight);
  }

  .coming-soon p {
    font-size: var(--text-base);
    max-width: 320px;
  }
</style>
