<script lang="ts">
  import { setThemePreference, themePreference } from '../composables/themePreference.svelte'
  import type { ThemePreference } from '../validation/schemas'

  interface ThemeOption {
    preference: ThemePreference
    label: string
  }

  /* System first: it is the default and communicates "follow the OS unless
     you override", matching the order users meet in OS settings. */
  const themeOptions: ThemeOption[] = [
    { preference: 'system', label: 'System' },
    { preference: 'light', label: 'Light' },
    { preference: 'dark', label: 'Dark' },
  ]

  let mobileMenuOpen = $state(false)
  let toggleRoot = $state<HTMLDivElement | null>(null)

  let activeOption = $derived(
    themeOptions.find((themeOption) => themeOption.preference === themePreference.preference) ??
      themeOptions[0],
  )

  function selectThemePreference(preference: ThemePreference): void {
    setThemePreference(preference)
    mobileMenuOpen = false
  }

  function closeMenuOnOutsideClick(clickEvent: MouseEvent): void {
    if (
      mobileMenuOpen &&
      toggleRoot &&
      clickEvent.target instanceof Node &&
      !toggleRoot.contains(clickEvent.target)
    ) {
      mobileMenuOpen = false
    }
  }

  function closeMenuOnEscape(keyboardEvent: KeyboardEvent): void {
    if (keyboardEvent.key === 'Escape') {
      mobileMenuOpen = false
    }
  }
</script>

<svelte:window onclick={closeMenuOnOutsideClick} onkeydown={closeMenuOnEscape} />

<div class="theme-toggle" bind:this={toggleRoot}>
  <!-- Compact trigger: only shown on narrow viewports where the full
       segmented control cannot fit the header row. -->
  <button
    type="button"
    class="theme-trigger"
    aria-haspopup="true"
    aria-expanded={mobileMenuOpen}
    aria-label="Color scheme: {activeOption.label}"
    title="Color scheme: {activeOption.label}"
    onclick={() => (mobileMenuOpen = !mobileMenuOpen)}
  >
    {#if activeOption.preference === 'system'}
      <svg class="theme-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
        <line x1="8" y1="21" x2="16" y2="21" />
        <line x1="12" y1="17" x2="12" y2="21" />
      </svg>
    {:else if activeOption.preference === 'light'}
      <svg class="theme-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <circle cx="12" cy="12" r="4" />
        <line x1="12" y1="2" x2="12" y2="4" />
        <line x1="12" y1="20" x2="12" y2="22" />
        <line x1="4.93" y1="4.93" x2="6.34" y2="6.34" />
        <line x1="17.66" y1="17.66" x2="19.07" y2="19.07" />
        <line x1="2" y1="12" x2="4" y2="12" />
        <line x1="20" y1="12" x2="22" y2="12" />
        <line x1="4.93" y1="19.07" x2="6.34" y2="17.66" />
        <line x1="17.66" y1="6.34" x2="19.07" y2="4.93" />
      </svg>
    {:else}
      <svg class="theme-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
      </svg>
    {/if}
  </button>

  <div class="theme-options" class:open={mobileMenuOpen} role="group" aria-label="Color scheme">
    {#each themeOptions as themeOption (themeOption.preference)}
      <button
        type="button"
        class="theme-option"
        class:active={themePreference.preference === themeOption.preference}
        aria-pressed={themePreference.preference === themeOption.preference}
        aria-label="{themeOption.label} color scheme"
        title="{themeOption.label} color scheme"
        onclick={() => selectThemePreference(themeOption.preference)}
      >
        {#if themeOption.preference === 'system'}
          <svg class="theme-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
            <line x1="8" y1="21" x2="16" y2="21" />
            <line x1="12" y1="17" x2="12" y2="21" />
          </svg>
        {:else if themeOption.preference === 'light'}
          <svg class="theme-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="4" />
            <line x1="12" y1="2" x2="12" y2="4" />
            <line x1="12" y1="20" x2="12" y2="22" />
            <line x1="4.93" y1="4.93" x2="6.34" y2="6.34" />
            <line x1="17.66" y1="17.66" x2="19.07" y2="19.07" />
            <line x1="2" y1="12" x2="4" y2="12" />
            <line x1="20" y1="12" x2="22" y2="12" />
            <line x1="4.93" y1="19.07" x2="6.34" y2="17.66" />
            <line x1="17.66" y1="6.34" x2="19.07" y2="4.93" />
          </svg>
        {:else}
          <svg class="theme-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
          </svg>
        {/if}
        <span class="theme-option-label">{themeOption.label}</span>
      </button>
    {/each}
  </div>
</div>

<style>
  .theme-toggle {
    position: relative;
    display: flex;
    align-items: center;
  }

  /* Mobile-only compact trigger; hidden on wider viewports. */
  .theme-trigger {
    display: none;
  }

  /* Mirrors the header's nav-tabs segmented control so the toggle reads as
     part of the same chrome rather than a foreign widget. */
  .theme-options {
    display: flex;
    gap: var(--space-1);
    background: var(--color-surface-subtle);
    padding: var(--space-1);
    border-radius: var(--radius-lg);
    border: 1px solid var(--color-border-subtle);
  }

  .theme-option {
    display: flex;
    align-items: center;
    justify-content: center;
    min-width: 40px;
    min-height: 40px;
    padding: var(--space-2);
    color: var(--color-text-secondary);
    background: transparent;
    border: none;
    border-radius: var(--radius-md);
    cursor: pointer;
    transition:
      color var(--duration-fast) var(--ease-out),
      background-color var(--duration-fast) var(--ease-out),
      box-shadow var(--duration-fast) var(--ease-out),
      transform var(--duration-fast) var(--ease-out);
  }

  .theme-option:hover:not(.active) {
    color: var(--color-text-primary);
    background: var(--color-surface-hover);
  }

  .theme-option:active {
    transform: scale(0.96);
  }

  .theme-option.active {
    color: var(--color-text-primary);
    background: var(--color-bg-elevated);
    box-shadow: var(--shadow-sm);
  }

  /* Labels only render inside the mobile dropdown; the desktop segmented
     control stays icon-only to keep the header calm. */
  .theme-option-label {
    display: none;
    font-size: var(--text-sm);
    font-weight: 500;
  }

  .theme-icon {
    width: 16px;
    height: 16px;
    flex-shrink: 0;
    opacity: 0.7;
    transition: opacity var(--duration-fast) var(--ease-out);
  }

  .theme-option:hover .theme-icon,
  .theme-option.active .theme-icon,
  .theme-trigger .theme-icon {
    opacity: 1;
  }

  /* Narrow viewports: the segmented control collapses into a 44px trigger
     plus a dropdown so brand + nav + toggle + auth still fit a 360px row. */
  @media (max-width: 640px) {
    .theme-trigger {
      display: flex;
      align-items: center;
      justify-content: center;
      min-width: 44px;
      min-height: 44px;
      padding: var(--space-2);
      color: var(--color-text-secondary);
      background: transparent;
      border: 1px solid var(--color-border-subtle);
      border-radius: var(--radius-md);
      cursor: pointer;
      transition:
        color var(--duration-fast) var(--ease-out),
        background-color var(--duration-fast) var(--ease-out),
        transform var(--duration-fast) var(--ease-out);
    }

    .theme-trigger:hover {
      color: var(--color-text-primary);
      background: var(--color-surface-hover);
    }

    .theme-trigger:active {
      transform: scale(0.96);
    }

    .theme-trigger .theme-icon {
      width: 20px;
      height: 20px;
    }

    .theme-options {
      position: absolute;
      top: calc(100% + var(--space-2));
      right: 0;
      z-index: 110;
      flex-direction: column;
      gap: 0;
      min-width: 148px;
      padding: var(--space-1);
      background: var(--color-bg-elevated);
      border: 1px solid var(--color-border-default);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-lg);
      opacity: 0;
      visibility: hidden;
      transform: translateY(-4px);
      transition:
        opacity var(--duration-fast) var(--ease-out),
        transform var(--duration-fast) var(--ease-out),
        visibility var(--duration-fast);
    }

    .theme-options.open {
      opacity: 1;
      visibility: visible;
      transform: translateY(0);
    }

    .theme-option {
      justify-content: flex-start;
      gap: var(--space-3);
      width: 100%;
      min-height: 44px;
      padding: var(--space-2) var(--space-3);
    }

    .theme-option-label {
      display: inline;
    }

    .theme-option .theme-icon {
      width: 20px;
      height: 20px;
    }
  }
</style>
