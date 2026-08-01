<script lang="ts">
  import {
    attachUserButton,
    clerkAuthentication,
    openSignIn,
    openSignUp,
  } from '../composables/clerkAuthentication.svelte'
  import type { ApplicationView } from '../services/pageMetadata'
  import HeaderMenu from './HeaderMenu.svelte'

  interface Props {
    currentView: ApplicationView
  }

  let { currentView = $bindable('chat') }: Props = $props()
</script>

<header class="header">
  <div class="header-inner">
    <a href="/" class="brand" aria-label="Java Chat Home">
      <img
        class="brand-mark"
        src="/assets/javachat_cup_star_256.png"
        alt=""
        aria-hidden="true"
      />
      <span class="brand-text">Java Chat</span>
    </a>

    <nav class="nav-tabs" aria-label="Main navigation">
      <button
        type="button"
        class="nav-tab"
        class:active={currentView === 'chat'}
        aria-label="Chat"
        aria-current={currentView === 'chat' ? 'page' : undefined}
        onclick={() => currentView = 'chat'}
      >
        <svg class="nav-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path fill-rule="evenodd" d="M10 2c-4.411 0-8 2.91-8 6.5 0 1.778.785 3.4 2.071 4.615l-.614 2.307a.5.5 0 0 0 .695.577l2.756-1.103A9.1 9.1 0 0 0 10 15.5c4.411 0 8-2.91 8-6.5S14.411 2 10 2Z" clip-rule="evenodd"/>
        </svg>
        <span>Chat</span>
      </button>

      <button
        type="button"
        class="nav-tab"
        class:active={currentView === 'learn'}
        aria-label="Learn"
        aria-current={currentView === 'learn' ? 'page' : undefined}
        onclick={() => currentView = 'learn'}
      >
        <svg class="nav-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path d="M10.75 16.82A7.462 7.462 0 0 1 15 15.5c.71 0 1.396.098 2.046.282A.75.75 0 0 0 18 15.06v-11a.75.75 0 0 0-.546-.721A9.006 9.006 0 0 0 15 3a8.963 8.963 0 0 0-4.25 1.065V16.82ZM9.25 4.065A8.963 8.963 0 0 0 5 3c-.85 0-1.673.118-2.454.339A.75.75 0 0 0 2 4.06v11a.75.75 0 0 0 .954.721A7.462 7.462 0 0 1 5 15.5c1.579 0 3.042.487 4.25 1.32V4.065Z"/>
        </svg>
        <span>Learn</span>
      </button>
      </nav>

    {#if clerkAuthentication.isLoaded}
      <div class="auth-controls">
        {#if clerkAuthentication.signedInUser}
          <div class="user-button-host" {@attach attachUserButton}></div>
        {:else}
          <button type="button" class="auth-button" onclick={() => openSignIn()}>
            Sign in
          </button>
          <button type="button" class="auth-button auth-button-primary" onclick={() => openSignUp()}>
            Sign up
          </button>
          <!-- Narrow viewports: one icon button keeps the header row within
               360px; Clerk's sign-in form links to sign-up. -->
          <button
            type="button"
            class="auth-button-icon"
            aria-label="Sign in"
            title="Sign in"
            onclick={() => openSignIn()}
          >
            <svg class="nav-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path d="M10 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM3.465 14.493a1.23 1.23 0 0 0 .41 1.412A9.957 9.957 0 0 0 10 18c2.31 0 4.438-.784 6.131-2.1.43-.333.604-.903.408-1.41a7.002 7.002 0 0 0-13.074.003Z"/>
            </svg>
          </button>
        {/if}
      </div>
    {/if}

    <HeaderMenu bind:currentView />
  </div>
</header>

<style>
  .header {
    position: sticky;
    top: 0;
    z-index: 100;
    background: var(--color-bg-primary);
    border-bottom: 1px solid var(--color-border-subtle);
  }

  .header-inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    max-width: 1400px;
    margin: 0 auto;
    padding: var(--space-3) var(--space-6);
    gap: var(--space-8);
  }

  /* Brand */
  .brand {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    text-decoration: none;
    color: inherit;
    transition: opacity var(--duration-fast) var(--ease-out);
  }

  .brand:hover {
    opacity: 0.85;
  }

  .brand-mark {
    width: 32px;
    height: 32px;
    border-radius: var(--radius-md);
    object-fit: contain;
  }

  .brand-text {
    /* Times New Roman: Fraunces renders its small-optical-size J design (stub
       hook) at this size in Safari, and its STAT table blocks any CSS
       correction there. */
    font-family: "Times New Roman", Times, serif;
    font-size: var(--text-xl);
    font-weight: 500;
    letter-spacing: var(--tracking-tight);
  }

  /* Navigation */
  .nav-tabs {
    display: flex;
    gap: var(--space-1);
    background: var(--color-surface-subtle);
    padding: var(--space-1);
    border-radius: var(--radius-lg);
    border: 1px solid var(--color-border-subtle);
  }

  .nav-tab {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-height: 40px;
    padding: var(--space-2) var(--space-4);
    font-size: var(--text-sm);
    font-weight: 500;
    color: var(--color-text-secondary);
    background: transparent;
    border: none;
    border-radius: var(--radius-md);
    cursor: pointer;
    transition:
      color var(--duration-fast) var(--ease-out),
      background-color var(--duration-fast) var(--ease-out),
      box-shadow var(--duration-fast) var(--ease-out);
  }

  .nav-tab:hover:not(.active) {
    color: var(--color-text-primary);
    background: var(--color-surface-hover);
  }

  .nav-tab.active {
    color: var(--color-text-primary);
    background: var(--color-bg-elevated);
    box-shadow: var(--shadow-sm);
  }

  .nav-icon {
    width: 16px;
    height: 16px;
    opacity: 0.7;
    transition: opacity var(--duration-fast) var(--ease-out);
  }

  .nav-tab:hover .nav-icon,
  .nav-tab.active .nav-icon {
    opacity: 1;
  }

  /* Authentication */
  .auth-controls {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .auth-button {
    min-height: 40px;
    padding: var(--space-2) var(--space-4);
    font-size: var(--text-sm);
    font-weight: 500;
    color: var(--color-text-secondary);
    background: transparent;
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-md);
    cursor: pointer;
    transition:
      color var(--duration-fast) var(--ease-out),
      background-color var(--duration-fast) var(--ease-out),
      box-shadow var(--duration-fast) var(--ease-out);
  }

  .auth-button:hover {
    color: var(--color-text-primary);
    background: var(--color-surface-hover);
  }

  .auth-button-primary {
    color: var(--color-text-primary);
    background: var(--color-bg-elevated);
    box-shadow: var(--shadow-sm);
  }

  .user-button-host {
    display: flex;
    align-items: center;
    min-width: 28px;
    min-height: 28px;
  }

  /* Narrow viewports swap the two text buttons for this single icon button
     (see the ≤640px media query). */
  .auth-button-icon {
    display: none;
  }


  /* Tablet and small laptops: icon-only navigation keeps brand + nav + auth +
     menu within the row (with labels the header needs ~985px, so anything
     under 1024 would squeeze or wrap the brand). */
  @media (max-width: 1024px) {
    .header-inner {
      padding: var(--space-3) var(--space-4);
      gap: var(--space-4);
    }

    .nav-tab span {
      display: none;
    }

    .nav-tab {
      min-height: 44px; /* Touch target */
    }

    .nav-icon {
      width: 20px;
      height: 20px;
    }

    .auth-button {
      min-height: 44px; /* Touch target */
    }
  }

  /* Mobile */
  @media (max-width: 640px) {
    .header-inner {
      padding: var(--space-2) var(--space-3);
      gap: var(--space-3);
    }

    .brand-text {
      display: none;
    }

    .brand-mark {
      width: 36px;
      height: 36px;
    }

    .nav-tab {
      padding: var(--space-2) var(--space-3);
    }

    .auth-button {
      display: none;
    }

    .auth-button-icon {
      display: flex;
      align-items: center;
      justify-content: center;
      min-width: 44px; /* Touch target */
      min-height: 44px;
      padding: var(--space-2);
      color: var(--color-text-secondary);
      background: transparent;
      border: 1px solid var(--color-border-subtle);
      border-radius: var(--radius-md);
      cursor: pointer;
      transition:
        color var(--duration-fast) var(--ease-out),
        background-color var(--duration-fast) var(--ease-out);
    }

    .auth-button-icon:hover {
      color: var(--color-text-primary);
      background: var(--color-surface-hover);
    }
  }

  /* Small phones */
  @media (max-width: 380px) {
    .header-inner {
      padding: var(--space-2);
      gap: var(--space-2);
    }

    .brand-mark {
      width: 32px;
      height: 32px;
    }

    .nav-tabs {
      padding: 2px;
    }

    .nav-tab {
      justify-content: center;
      min-width: 40px; /* Touch target */
      padding: var(--space-2);
    }
  }
</style>
