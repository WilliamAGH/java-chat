/**
 * Covers the environment gates of {@link loadClerkAuthentication}: browsers
 * that deny site storage (kiosk / hardened-privacy frames make the
 * `window.localStorage` getter throw) and builds without a publishable key
 * must disable auth quietly — no toast, no rejected promise, controls hidden.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { get } from "svelte/store";

const originalLocalStorageDescriptor = Object.getOwnPropertyDescriptor(window, "localStorage");

/**
 * Mirrors Chromium's behavior when site data is blocked: the property getter
 * itself throws a SecurityError before any storage method can be called.
 */
function denySiteStorageAccess(): void {
  Object.defineProperty(window, "localStorage", {
    configurable: true,
    get() {
      throw new DOMException(
        "Failed to read the 'localStorage' property from 'Window': Access is denied for this document.",
        "SecurityError",
      );
    },
  });
}

function restoreSiteStorageAccess(): void {
  if (originalLocalStorageDescriptor) {
    Object.defineProperty(window, "localStorage", originalLocalStorageDescriptor);
  } else {
    // No own descriptor to restore (storage inherited from the prototype):
    // deleting the override re-exposes the inherited accessor for later tests.
    Reflect.deleteProperty(window, "localStorage");
  }
}

async function importClerkAuthenticationModule() {
  return import("./clerkAuthentication.svelte");
}

async function importToastStoreModule() {
  return import("../stores/toastStore");
}

beforeEach(() => {
  vi.resetModules();
});

afterEach(() => {
  restoreSiteStorageAccess();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("loadClerkAuthentication", () => {
  it("disables auth quietly when the browser denies site storage access", async () => {
    vi.stubEnv("VITE_CLERK_PUBLISHABLE_KEY", "pk_test_storage-gate");
    const consoleInfoSpy = vi.spyOn(console, "info").mockImplementation(() => {});
    denySiteStorageAccess();
    const { loadClerkAuthentication, clerkAuthentication } =
      await importClerkAuthenticationModule();
    const { toasts } = await importToastStoreModule();

    await expect(loadClerkAuthentication()).resolves.toBeUndefined();

    expect(clerkAuthentication.phase).toBe("disabled");
    expect(get(toasts)).toEqual([]);
    expect(consoleInfoSpy).toHaveBeenCalledWith(
      expect.stringContaining("denies site storage access"),
      expect.any(DOMException),
    );
  });

  it("disables auth quietly when the build has no publishable key", async () => {
    vi.stubEnv("VITE_CLERK_PUBLISHABLE_KEY", "");
    const consoleInfoSpy = vi.spyOn(console, "info").mockImplementation(() => {});
    const { loadClerkAuthentication, clerkAuthentication } =
      await importClerkAuthenticationModule();
    const { toasts } = await importToastStoreModule();

    await expect(loadClerkAuthentication()).resolves.toBeUndefined();

    expect(clerkAuthentication.phase).toBe("disabled");
    expect(get(toasts)).toEqual([]);
    expect(consoleInfoSpy).toHaveBeenCalledWith(
      expect.stringContaining("no VITE_CLERK_PUBLISHABLE_KEY"),
    );
  });

  it("rejects malformed Clerk API-key secrets without logging the returned value", async () => {
    const malformedSecret = "malformed-sensitive-key-value";
    const apiKeyCreateMock = vi.fn().mockResolvedValue({ secret: malformedSecret });
    vi.stubEnv("VITE_CLERK_PUBLISHABLE_KEY", "pk_test_api-key-validation");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true }));
    vi.doMock("@clerk/clerk-js", () => ({
      Clerk: class {
        user = { id: "user_cli" };
        apiKeys = { create: apiKeyCreateMock };

        async load(): Promise<void> {}

        addListener(): void {}
      },
    }));
    vi.doMock("@clerk/ui", () => ({ ui: {} }));
    vi.doMock("@clerk/ui/themes", () => ({ dark: {} }));
    const consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const { createCliApiKey, loadClerkAuthentication } = await importClerkAuthenticationModule();

    await loadClerkAuthentication();

    await expect(createCliApiKey("workstation")).rejects.toThrow(
      "Clerk created an API key without returning its one-time secret.",
    );
    expect(apiKeyCreateMock).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(consoleErrorSpy.mock.calls)).not.toContain(malformedSecret);
  });
});
