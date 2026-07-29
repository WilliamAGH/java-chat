<script lang="ts">
  import { onMount } from 'svelte'
  import Header from './lib/components/Header.svelte'
  import ChatView from './lib/components/ChatView.svelte'
  import LearnView from './lib/components/LearnView.svelte'
  import ToastContainer from './lib/components/ToastContainer.svelte'
  import { refreshCsrfToken } from './lib/services/csrf'
  import { loadClerkAuthentication } from './lib/composables/clerkAuthentication.svelte'
  import {
    applicationViewForPath,
    canonicalRecoveryPathForPath,
    canonicalPathForApplicationView,
    lessonSlugForPath,
    synchronizeDocumentMetadata,
    type ApplicationView,
  } from './lib/services/pageMetadata'

  let currentView = $state<ApplicationView>(applicationViewForPath(globalThis.location.pathname))
  let currentLessonSlug = $state<string | null>(lessonSlugForPath(globalThis.location.pathname))

  $effect(() => {
    recoverUnimplementedLessonRoute()
    if (applicationViewForPath(globalThis.location.pathname) !== currentView) {
      const selectedViewPath = canonicalPathForApplicationView(currentView)
      globalThis.history.pushState({}, '', selectedViewPath)
    } else if (currentView === 'learn') {
      synchronizeLessonRouteWithSelection()
    }
    synchronizeDocumentMetadata()
  })

  onMount(() => {
    void refreshCsrfToken()
    // Failure already surfaced to the user as a toast inside the composable;
    // rethrown error lands in the console for diagnostics ([RC1f]: no silence).
    loadClerkAuthentication().catch((clerkLoadFailure: unknown) => {
      console.error('Clerk authentication failed to initialize', clerkLoadFailure)
    })
  })

  function synchronizeViewWithBrowserHistory(): void {
    recoverUnimplementedLessonRoute()
    currentView = applicationViewForPath(globalThis.location.pathname)
    currentLessonSlug = lessonSlugForPath(globalThis.location.pathname)
    synchronizeDocumentMetadata()
  }

  function synchronizeLessonRouteWithSelection(): void {
    const pathLessonSlug = lessonSlugForPath(globalThis.location.pathname)
    if (currentLessonSlug && pathLessonSlug !== currentLessonSlug) {
      globalThis.history.pushState({}, '', `/learn/${currentLessonSlug}`)
    } else if (!currentLessonSlug && pathLessonSlug) {
      globalThis.history.pushState({}, '', '/learn')
    }
  }

  function recoverUnimplementedLessonRoute(): void {
    const canonicalRecoveryPath = canonicalRecoveryPathForPath(globalThis.location.pathname)
    if (canonicalRecoveryPath) {
      globalThis.history.replaceState({}, '', canonicalRecoveryPath)
    }
  }
</script>

<svelte:window onpopstate={synchronizeViewWithBrowserHistory} />

<div class="app-shell">
  <Header bind:currentView />

  <main class="main-content">
    {#if currentView === 'chat'}
      <ChatView />
    {:else}
      <LearnView bind:selectedSlug={currentLessonSlug} />
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
