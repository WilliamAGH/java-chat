import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const THEME_PREFERENCE_STORAGE_KEY = "java-chat-theme-preference";
const SYSTEM_COLOR_SCHEME_QUERY = "(prefers-color-scheme: dark)";

let systemPrefersDark = false;
let capturedColorSchemeListener: (() => void) | null = null;

/**
 * Replaces the jsdom-wide matchMedia stub with one whose dark-scheme answer
 * tests can flip, and which captures the composable's change listener so
 * tests can simulate the OS switching color schemes mid-session.
 */
function installColorSchemeMatchMedia(): void {
  capturedColorSchemeListener = null;
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: (query: string) => ({
      matches:
        query === SYSTEM_COLOR_SCHEME_QUERY ? systemPrefersDark : query === "(pointer: fine)",
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: (_eventName: string, listener: () => void) => {
        capturedColorSchemeListener = listener;
      },
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}

async function importThemePreferenceModule() {
  return import("./themePreference.svelte");
}

beforeEach(() => {
  vi.resetModules();
  localStorage.clear();
  systemPrefersDark = false;
  installColorSchemeMatchMedia();
  document.documentElement.removeAttribute("data-theme");
  document.documentElement.style.colorScheme = "";
  const themeColorMeta = document.createElement("meta");
  themeColorMeta.setAttribute("name", "theme-color");
  themeColorMeta.setAttribute("content", "#1a1a18");
  document.head.appendChild(themeColorMeta);
});

afterEach(() => {
  document.querySelector('meta[name="theme-color"]')?.remove();
  vi.restoreAllMocks();
});

describe("initializeThemePreference", () => {
  it("defaults to system and resolves dark when the OS prefers dark", async () => {
    systemPrefersDark = true;
    const { initializeThemePreference, themePreference } = await importThemePreferenceModule();

    initializeThemePreference();

    expect(themePreference.preference).toBe("system");
    expect(themePreference.resolvedTheme).toBe("dark");
    expect(document.documentElement.dataset["theme"]).toBe("dark");
    expect(document.documentElement.style.colorScheme).toBe("dark");
  });

  it("resolves light when the OS prefers light", async () => {
    systemPrefersDark = false;
    const { initializeThemePreference, themePreference } = await importThemePreferenceModule();

    initializeThemePreference();

    expect(themePreference.preference).toBe("system");
    expect(themePreference.resolvedTheme).toBe("light");
    expect(document.documentElement.dataset["theme"]).toBe("light");
  });

  it("honors a stored explicit override over the OS setting", async () => {
    systemPrefersDark = true;
    localStorage.setItem(THEME_PREFERENCE_STORAGE_KEY, "light");
    const { initializeThemePreference, themePreference } = await importThemePreferenceModule();

    initializeThemePreference();

    expect(themePreference.preference).toBe("light");
    expect(document.documentElement.dataset["theme"]).toBe("light");
  });

  it("discards a corrupt stored preference, logs it, and falls back to system", async () => {
    systemPrefersDark = true;
    localStorage.setItem(THEME_PREFERENCE_STORAGE_KEY, "neon");
    const consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const { initializeThemePreference, themePreference } = await importThemePreferenceModule();

    initializeThemePreference();

    expect(themePreference.preference).toBe("system");
    expect(localStorage.getItem(THEME_PREFERENCE_STORAGE_KEY)).toBeNull();
    expect(consoleErrorSpy).toHaveBeenCalled();
    expect(document.documentElement.dataset["theme"]).toBe("dark");
  });

  it("updates the browser-chrome theme-color meta with the resolved theme", async () => {
    systemPrefersDark = false;
    const { initializeThemePreference } = await importThemePreferenceModule();

    initializeThemePreference();

    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute("content")).toBe(
      "#faf9f6",
    );
  });
});

describe("setThemePreference", () => {
  it("persists the selection and applies it to the document", async () => {
    const { initializeThemePreference, setThemePreference, themePreference } =
      await importThemePreferenceModule();
    initializeThemePreference();

    setThemePreference("dark");

    expect(themePreference.preference).toBe("dark");
    expect(localStorage.getItem(THEME_PREFERENCE_STORAGE_KEY)).toBe("dark");
    expect(document.documentElement.dataset["theme"]).toBe("dark");
  });

  it("follows OS color-scheme changes only while the preference is system", async () => {
    systemPrefersDark = false;
    const { initializeThemePreference, setThemePreference, themePreference } =
      await importThemePreferenceModule();
    initializeThemePreference();
    expect(capturedColorSchemeListener).not.toBeNull();

    systemPrefersDark = true;
    capturedColorSchemeListener!();
    expect(themePreference.resolvedTheme).toBe("dark");

    setThemePreference("light");
    systemPrefersDark = false;
    capturedColorSchemeListener!();
    expect(themePreference.resolvedTheme).toBe("light");
    expect(document.documentElement.dataset["theme"]).toBe("light");
  });

  it("returns to tracking the OS when the user selects system again", async () => {
    systemPrefersDark = true;
    const { initializeThemePreference, setThemePreference, themePreference } =
      await importThemePreferenceModule();
    initializeThemePreference();
    setThemePreference("light");

    setThemePreference("system");

    expect(themePreference.resolvedTheme).toBe("dark");
    expect(localStorage.getItem(THEME_PREFERENCE_STORAGE_KEY)).toBe("system");
  });
});
