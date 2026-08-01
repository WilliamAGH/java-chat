/**
 * Owns the color-scheme preference lifecycle: System/Dark/Light selection,
 * localStorage persistence, and DOM application.
 *
 * The resolved theme reaches the page through `data-theme` on `<html>`, which
 * global.css uses to swap the design-token palette. First paint is handled by
 * the inline boot script in index.html (it runs before any stylesheet), so
 * this module only needs to wire reactivity and keep the DOM in sync from
 * `main.ts` onward — the two share the storage key and must stay consistent.
 */

import { ThemePreferenceSchema, type ThemePreference } from "../validation/schemas";
import { logZodFailure } from "../validation/validate";

/** The theme actually rendered, after resolving `system` through the OS setting. */
export type ResolvedTheme = "light" | "dark";

/** localStorage key shared with the pre-paint boot script in index.html. */
const THEME_PREFERENCE_STORAGE_KEY = "java-chat-theme-preference";

const SYSTEM_COLOR_SCHEME_QUERY = "(prefers-color-scheme: dark)";

/**
 * Browser-chrome colors per theme, applied to the `theme-color` meta tag.
 * Kept in sync with `--color-bg-primary` in global.css so the OS window
 * frame matches the page background from the very first paint.
 */
const THEME_COLOR_HEX_PER_THEME: Record<ResolvedTheme, string> = {
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
 * Reads a previously stored preference, falling back to `system` when absent
 * or corrupt. Corrupt values are removed so the next visit starts clean; the
 * failure is logged per [FV1d] rather than silently ignored.
 */
function readStoredThemePreference(): ThemePreference {
  const storedPreference = localStorage.getItem(THEME_PREFERENCE_STORAGE_KEY);
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
    localStorage.removeItem(THEME_PREFERENCE_STORAGE_KEY);
    return "system";
  }
  return parsedPreference.data;
}

/**
 * Restores the stored preference, applies the resolved theme, and starts
 * following OS color-scheme changes while `system` is selected. Safe to call
 * once from `main.ts`; the media-query listener is attached only once.
 */
export function initializeThemePreference(): void {
  themePreference.preference = readStoredThemePreference();
  if (!systemColorSchemeQuery) {
    systemColorSchemeQuery = window.matchMedia(SYSTEM_COLOR_SCHEME_QUERY);
    systemColorSchemeQuery.addEventListener("change", handleSystemColorSchemeChange);
  }
  synchronizeWithPreference();
}

/** Persists and applies a new preference from the theme toggle. */
export function setThemePreference(nextPreference: ThemePreference): void {
  themePreference.preference = nextPreference;
  localStorage.setItem(THEME_PREFERENCE_STORAGE_KEY, nextPreference);
  synchronizeWithPreference();
}
