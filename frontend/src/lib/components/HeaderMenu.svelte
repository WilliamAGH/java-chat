<script lang="ts">
  import { setThemePreference, themePreference } from '../composables/themePreference.svelte'
  import type { ApplicationView } from '../services/pageMetadata'
  import type { ThemePreference } from '../validation/schemas'

  /**
   * Unified header menu holding the color-scheme choice and the public page
   * links (Privacy, Contact). One implementation serves every viewport — the
   * main navigation stays limited to the learning surfaces (Chat, Learn).
   *
   * Closes on outside click and Escape, and returns focus to the trigger so
   * keyboard users never lose their place (WCAG 2.4.3).
   */
  interface Props {
    currentView: ApplicationView
  }

  let { currentView = $bindable('chat') }: Props = $props()

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

  interface SiteLink {
    view: ApplicationView
    path: string
    label: string
  }

  const siteLinks: SiteLink[] = [
    { view: 'privacy', path: '/privacy', label: 'Privacy' },
    { view: 'contact', path: '/contact', label: 'Contact' },
  ]

  let menuOpen = $state(false)
  let menuRoot = $state<HTMLDivElement | null>(null)
  let triggerButton = $state<HTMLButtonElement | null>(null)

  function closeMenu(): void {
    if (!menuOpen) {
      return
    }
    menuOpen = false
    // Focus would otherwise drop to <body> when the panel hides (WCAG 2.4.3).
    triggerButton?.focus()
  }

  function selectThemePreference(preference: ThemePreference): void {
    setThemePreference(preference)
  }

  function navigateToSiteLink(clickEvent: MouseEvent, siteLink: SiteLink): void {
    clickEvent.preventDefault()
    currentView = siteLink.view
    closeMenu()
  }

  function closeMenuOnOutsideClick(clickEvent: MouseEvent): void {
    if (menuOpen && menuRoot && clickEvent.target instanceof Node && !menuRoot.contains(clickEvent.target)) {
      closeMenu()
    }
  }

  function closeMenuOnEscape(keyboardEvent: KeyboardEvent): void {
    if (keyboardEvent.key === 'Escape' && menuOpen) {
      closeMenu()
    }
  }
</script>

<svelte:window onclick={closeMenuOnOutsideClick} onkeydown={closeMenuOnEscape} />

<div class="header-menu" bind:this={menuRoot}>
  <button
    type="button"
    class="menu-trigger"
    bind:this={triggerButton}
    aria-haspopup="menu"
    aria-expanded={menuOpen}
    aria-label="Settings and pages menu"
    title="Settings and pages"
    onclick={() => (menuOpen = !menuOpen)}
  >
    <svg class="menu-trigger-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path fill-rule="evenodd" d="M2 4.75A.75.75 0 0 1 2.75 4h14.5a.75.75 0 0 1 0 1.5H2.75A.75.75 0 0 1 2 4.75ZM2 10a.75.75 0 0 1 .75-.75h14.5a.75.75 0 0 1 0 1.5H2.75A.75.75 0 0 1 2 10Zm0 5.25a.75.75 0 0 1 .75-.75h14.5a.75.75 0 0 1 0 1.5H2.75a.75.75 0 0 1-.75-.75Z" clip-rule="evenodd"/>
    </svg>
  </button>

  <div class="menu-panel" class:open={menuOpen} role="menu" aria-label="Settings and pages">
    <div class="menu-section" role="group" aria-label="Color scheme">
      <p class="menu-caption">Color scheme</p>
      {#each themeOptions as themeOption (themeOption.preference)}
        <button
          type="button"
          class="menu-row"
          class:active={themePreference.preference === themeOption.preference}
          aria-pressed={themePreference.preference === themeOption.preference}
          aria-label="{themeOption.label} color scheme"
          onclick={() => selectThemePreference(themeOption.preference)}
        >
          {#if themeOption.preference === 'system'}
            <svg class="menu-row-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
              <line x1="8" y1="21" x2="16" y2="21" />
              <line x1="12" y1="17" x2="12" y2="21" />
            </svg>
          {:else if themeOption.preference === 'light'}
            <svg class="menu-row-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
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
            <svg class="menu-row-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
            </svg>
          {/if}
          <span class="menu-row-label">{themeOption.label}</span>
          {#if themePreference.preference === themeOption.preference}
            <svg class="menu-row-check" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M16.704 4.153a.75.75 0 0 1 .143 1.052l-8 10.5a.75.75 0 0 1-1.127.075l-4.5-4.5a.75.75 0 0 1 1.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 0 1 1.05-.143Z" clip-rule="evenodd"/>
            </svg>
          {/if}
        </button>
      {/each}
    </div>

    <hr class="menu-divider" />

    <div class="menu-section" role="group" aria-label="Pages">
      {#each siteLinks as siteLink (siteLink.view)}
        <a
          href={siteLink.path}
          class="menu-row"
          class:active={currentView === siteLink.view}
          aria-current={currentView === siteLink.view ? 'page' : undefined}
          role="menuitem"
          onclick={(clickEvent) => navigateToSiteLink(clickEvent, siteLink)}
        >
          {#if siteLink.view === 'privacy'}
            <svg class="menu-row-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M10 1.944A11.954 11.954 0 0 1 3.84 4.13a.75.75 0 0 0-.34.627v4.577c0 3.83 2.144 7.335 5.552 9.077a2.086 2.086 0 0 0 1.896 0C14.356 16.67 16.5 13.165 16.5 9.334V4.757a.75.75 0 0 0-.34-.627A11.954 11.954 0 0 1 10 1.944Zm3.03 6.586a.75.75 0 0 0-1.06-1.06L9 10.44 8.03 9.47a.75.75 0 0 0-1.06 1.06l1.5 1.5a.75.75 0 0 0 1.06 0l3.5-3.5Z" clip-rule="evenodd"/>
            </svg>
          {:else}
            <svg class="menu-row-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path d="M3 4a2 2 0 0 0-2 2v1.161l8.441 4.221a1.25 1.25 0 0 0 1.118 0L19 7.162V6a2 2 0 0 0-2-2H3Z"/>
              <path d="M19 8.839l-7.77 3.885a2.75 2.75 0 0 1-2.46 0L1 8.839V14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V8.839Z"/>
            </svg>
          {/if}
          <span class="menu-row-label">{siteLink.label}</span>
        </a>
      {/each}
    </div>
  </div>
</div>

<style>
  .header-menu {
    position: relative;
    display: flex;
    align-items: center;
  }

  .menu-trigger {
    display: flex;
    align-items: center;
    justify-content: center;
    min-width: 40px;
    min-height: 40px;
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

  .menu-trigger:hover {
    color: var(--color-text-primary);
    background: var(--color-surface-hover);
  }

  .menu-trigger:active {
    transform: scale(0.96);
  }

  .menu-trigger-icon {
    width: 18px;
    height: 18px;
  }

  /* One panel serves every viewport: no breakpoint-specific behavior. */
  .menu-panel {
    position: absolute;
    top: calc(100% + var(--space-2));
    right: 0;
    z-index: 110;
    min-width: 200px;
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

  .menu-panel.open {
    opacity: 1;
    visibility: visible;
    transform: translateY(0);
  }

  .menu-section {
    display: flex;
    flex-direction: column;
  }

  .menu-caption {
    padding: var(--space-2) var(--space-3) var(--space-1);
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: var(--tracking-wider);
    text-transform: uppercase;
    color: var(--color-text-tertiary);
  }

  .menu-divider {
    margin: var(--space-1) 0;
    border: none;
    border-top: 1px solid var(--color-border-subtle);
  }

  .menu-row {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    width: 100%;
    min-height: 44px;
    padding: var(--space-2) var(--space-3);
    font-size: var(--text-sm);
    font-weight: 500;
    color: var(--color-text-secondary);
    background: transparent;
    border: none;
    border-radius: var(--radius-md);
    cursor: pointer;
    text-decoration: none;
    transition:
      color var(--duration-fast) var(--ease-out),
      background-color var(--duration-fast) var(--ease-out);
  }

  .menu-row:hover:not(.active) {
    color: var(--color-text-primary);
    background: var(--color-surface-hover);
  }

  .menu-row.active {
    color: var(--color-text-primary);
    background: var(--color-bg-tertiary);
  }

  .menu-row-icon {
    width: 16px;
    height: 16px;
    flex-shrink: 0;
    opacity: 0.7;
    transition: opacity var(--duration-fast) var(--ease-out);
  }

  .menu-row:hover .menu-row-icon,
  .menu-row.active .menu-row-icon {
    opacity: 1;
  }

  .menu-row-label {
    flex: 1;
    text-align: left;
  }

  .menu-row-check {
    width: 14px;
    height: 14px;
    color: var(--color-accent);
  }

  @media (max-width: 1024px) {
    .menu-trigger {
      min-width: 44px; /* Touch target */
      min-height: 44px;
    }
  }
</style>
