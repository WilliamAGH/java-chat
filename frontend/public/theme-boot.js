/*
 * Theme boot: applies the persisted/system color scheme before first paint so
 * the wrong theme never flashes. Served as a static asset (not an inline
 * script) because the backend's Content-Security-Policy is script-src 'self'.
 * Classic render-blocking script on purpose — async/defer would paint first.
 *
 * Mirrors src/lib/composables/themePreference.svelte.ts — the two share the
 * storage key, fallback chain, and meta hex values, and must stay consistent
 * (guarded by a drift test in themePreference.svelte.test.ts).
 */
(function () {
  var storedPreference = null;
  try {
    storedPreference = localStorage.getItem("java-chat-theme-preference");
  } catch (storageError) {
    // localStorage unavailable (e.g. hardened privacy mode): fall through to system.
    console.warn("Theme preference unreadable, using system color scheme", storageError);
  }
  var resolvedTheme =
    storedPreference === "light" || storedPreference === "dark"
      ? storedPreference
      : typeof window.matchMedia === "function" &&
          window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";
  document.documentElement.dataset.theme = resolvedTheme;
  document.documentElement.style.colorScheme = resolvedTheme;
  var themeColorMeta = document.querySelector('meta[name="theme-color"]');
  if (themeColorMeta) {
    themeColorMeta.setAttribute("content", resolvedTheme === "dark" ? "#1a1a18" : "#faf9f6");
  }
})();
