/**
 * Owns the color-scheme preference lifecycle: System/Dark/Light selection,
 * localStorage persistence, cross-tab synchronization, and DOM application.
 *
 * The resolved theme reaches the page through `data-theme` on `<html>`, which
 * global.css uses to swap the design-token palette. First paint is handled by
 * the /theme-boot.js asset (a render-blocking classic script; the backend CSP
 * is script-src 'self'), so this module only needs to wire reactivity and keep
 * the DOM in sync from `main.ts` onward — the two share the storage key,
 * fallback chain, and meta hex values, and must stay consistent.
 */

import { ThemePreferenceSchema, type ThemePreference } from "../validation/schemas";
import { logZodFailure } from "../validation/validate";

/** The theme actually rendered, after resolving `system` through the OS setting. */
export type ResolvedTheme = "light" | "dark";

/** localStorage key shared with the pre-paint /theme-boot.js asset. */
export const THEME_PREFERENCE_STORAGE_KEY = "java-chat-theme-preference";

const SYSTEM_COLOR_SCHEME_QUERY = "(prefers-color-scheme: dark)";

/**
 * Browser-chrome colors per theme, applied to the `theme-color` meta tag.
 * Kept in sync with `--color-bg-primary` in global.css and the literals in
 * public/theme-boot.js so the OS window frame matches the page background
 * from the very first paint (drift guarded by themePreference tests).
 */
export const THEME_COLOR_HEX_PER_THEME: Record<ResolvedTheme, string> = {
  dark: "#1a1a18",
  light: "#faf9f6",
};

class ThemePreferenceState {
  /** Stored preference: `system` follows the OS, `light`/`dark` override it. */
  preference = $state<ThemePreference>("system");
  /** Theme currently applied to the document. */
  resolvedTheme = $state<ResolvedTheme>("dark");
}

export const themePreference = new ThemePreferenceState();

let systemColorSchemeQuery: MediaQueryList | null = null;

function systemColorScheme(): ResolvedTheme {
  return window.matchMedia(SYSTEM_COLOR_SCHEME_QUERY).matches ? "dark" : "light";
}

function applyResolvedTheme(resolvedTheme: ResolvedTheme): void {
  document.documentElement.dataset["theme"] = resolvedTheme;
  // color-scheme keeps native UI (scrollbars, form controls) on the same palette.
  document.documentElement.style.colorScheme = resolvedTheme;
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute("content", THEME_COLOR_HEX_PER_THEME[resolvedTheme]);
  themePreference.resolvedTheme = resolvedTheme;
}

function synchronizeWithPreference(): void {
  applyResolvedTheme(
    themePreference.preference === "system" ? systemColorScheme() : themePreference.preference,
  );
}

function handleSystemColorSchemeChange(): void {
  if (themePreference.preference === "system") {
    synchronizeWithPreference();
  }
}

/**
 * Reads a previously stored preference, falling back to `system` when absent,
 * unreadable, or corrupt. Corrupt values are removed so the next visit starts
 * clean; failures are logged per [FV1d] rather than silently ignored.
 * Storage itself can throw (hardened privacy modes), and that must never
 * prevent the app from booting — an unreadable store simply means `system`.
 */
function readStoredThemePreference(): ThemePreference {
  let storedPreference: string | null = null;
  try {
    storedPreference = localStorage.getItem(THEME_PREFERENCE_STORAGE_KEY);
  } catch (storageReadFailure) {
    console.warn("Theme preference unreadable, using system color scheme", storageReadFailure);
    return "system";
  }
  if (storedPreference === null) {
    return "system";
  }
  const parsedPreference = ThemePreferenceSchema.safeParse(storedPreference);
  if (!parsedPreference.success) {
    logZodFailure(
      `readStoredThemePreference [${THEME_PREFERENCE_STORAGE_KEY}]`,
      parsedPreference.error,
      storedPreference,
    );
    try {
      localStorage.removeItem(THEME_PREFERENCE_STORAGE_KEY);
    } catch (storageRemoveFailure) {
      console.warn("Corrupt theme preference could not be cleared", storageRemoveFailure);
    }
    return "system";
  }
  return parsedPreference.data;
}

/**
 * Persists the preference, tolerating storage failures (quota, privacy
 * modes): the theme still applies for the current session — it just will not
 * survive a reload.
 */
function persistThemePreference(preference: ThemePreference): void {
  try {
    localStorage.setItem(THEME_PREFERENCE_STORAGE_KEY, preference);
  } catch (storageWriteFailure) {
    console.warn("Theme preference could not be persisted", storageWriteFailure);
  }
}

/**
 * Applies a preference change made in another tab. The `storage` event only
 * fires for foreign tabs; a cleared key (null) means back to `system`.
 */
function handleCrossTabPreferenceChange(storageEvent: StorageEvent): void {
  if (storageEvent.key !== THEME_PREFERENCE_STORAGE_KEY) {
    return;
  }
  if (storageEvent.newValue === null) {
    themePreference.preference = "system";
    synchronizeWithPreference();
    return;
  }
  const parsedPreference = ThemePreferenceSchema.safeParse(storageEvent.newValue);
  if (!parsedPreference.success) {
    logZodFailure(
      `handleCrossTabPreferenceChange [${THEME_PREFERENCE_STORAGE_KEY}]`,
      parsedPreference.error,
      storageEvent.newValue,
    );
    return;
  }
  themePreference.preference = parsedPreference.data;
  synchronizeWithPreference();
}

/**
 * Restores the stored preference, applies the resolved theme, and starts
 * following OS color-scheme changes while `system` is selected, plus
 * preference changes made in other tabs. Safe to call once from `main.ts`;
 * the listeners are attached only once.
 */
export function initializeThemePreference(): void {
  themePreference.preference = readStoredThemePreference();
  if (!systemColorSchemeQuery) {
    systemColorSchemeQuery = window.matchMedia(SYSTEM_COLOR_SCHEME_QUERY);
    systemColorSchemeQuery.addEventListener("change", handleSystemColorSchemeChange);
    window.addEventListener("storage", handleCrossTabPreferenceChange);
  }
  synchronizeWithPreference();
}

/** Persists and applies a new preference from the theme toggle. */
export function setThemePreference(nextPreference: ThemePreference): void {
  themePreference.preference = nextPreference;
  persistThemePreference(nextPreference);
  synchronizeWithPreference();
}
