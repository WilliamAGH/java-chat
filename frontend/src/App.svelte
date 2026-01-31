<script lang="ts">
  import Header from './lib/components/Header.svelte'
  import ChatView from './lib/components/ChatView.svelte'
  import LearnView from './lib/components/LearnView.svelte'
  import ToastContainer from './lib/components/ToastContainer.svelte'

  let currentView = $state<'chat' | 'learn'>('chat')
</script>

<div class="app-shell">
  <Header bind:currentView />

  <main class="main-content">
    {#if currentView === 'chat'}
      <ChatView />
    {:else}
      <LearnView />
    {/if}
  </main>

  <ToastContainer />
</div>

<style>
  .app-shell {
    display: flex;
    flex-direction: column;
    height: 100vh;
    height: 100dvh;
    overflow: hidden;
  }

  .main-content {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
</style>
