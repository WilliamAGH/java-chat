<script lang="ts">
  import { onMount } from 'svelte'
  import Header from './lib/components/Header.svelte'
  import ChatView from './lib/components/ChatView.svelte'
  import LearnView from './lib/components/LearnView.svelte'
  import ToastContainer from './lib/components/ToastContainer.svelte'
  import { refreshCsrfToken } from './lib/services/csrf'
  import {
    applicationViewForPath,
    canonicalPathForApplicationView,
    synchronizeDocumentMetadata,
    type ApplicationView,
  } from './lib/services/pageMetadata'

  let currentView = $state<ApplicationView>(applicationViewForPath(globalThis.location.pathname))

  $effect(() => {
    if (applicationViewForPath(globalThis.location.pathname) !== currentView) {
      const selectedViewPath = canonicalPathForApplicationView(currentView)
      globalThis.history.pushState({}, '', selectedViewPath)
    }
    synchronizeDocumentMetadata()
  })

  onMount(() => {
    void refreshCsrfToken()
  })

  function synchronizeViewWithBrowserHistory(): void {
    currentView = applicationViewForPath(globalThis.location.pathname)
    synchronizeDocumentMetadata()
  }
</script>

<svelte:window onpopstate={synchronizeViewWithBrowserHistory} />

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
