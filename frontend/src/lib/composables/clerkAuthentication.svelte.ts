/**
 * Owns the Clerk authentication lifecycle and reactive session identity.
 *
 * Clerk ships no Svelte SDK, so this module wraps the vanilla
 * `@clerk/clerk-js` client (the officially supported path for Vite SPAs) in
 * module-level runes state. Components read {@link clerkAuthentication} for
 * render decisions and call the exported actions; nothing outside this file
 * touches the Clerk instance, keeping the SDK boundary in one place.
 */

import type { Clerk } from "@clerk/clerk-js";
import type { Appearance } from "@clerk/ui";
import { pushToast } from "../stores/toastStore";

/** Signed-in Clerk user, derived from the SDK's own typing ([TY1]: no transitive `@clerk/shared` import). */
export type ClerkSignedInUser = NonNullable<Clerk["user"]>;

class ClerkAuthenticationState {
  /** Authentication lifecycle phase; auth controls stay hidden until Clerk is ready. */
  phase = $state<"loading" | "disabled" | "ready" | "failed">("loading");
  /**
   * Current signed-in user or null. `$state.raw` because the SDK replaces the
   * resource wholesale on every emission; deep proxying a class instance
   * would break its prototype methods.
   */
  signedInUser = $state.raw<ClerkSignedInUser | null>(null);
}

export const clerkAuthentication = new ClerkAuthenticationState();

let clerkClient: Clerk | null = null;

/** Key read by the storage probe; never persisted, so any name outside real keys works. */
const STORAGE_ACCESS_PROBE_KEY = "java-chat-storage-access-probe";

/** Outcome of the site-storage probe; `denial` is the error the browser threw. */
type SiteStorageAccess = { accessible: true } | { accessible: false; denial: unknown };

/**
 * Probes whether this browser grants the page access to site storage.
 * Kiosk and hardened-privacy browsers (e.g. e-ink display frames with "block
 * site data" enabled) make the `window.localStorage` getter itself throw a
 * SecurityError; `@clerk/clerk-js` reads it unguarded during `Clerk.load()`
 * and cannot keep a session without it, so sign-in is impossible there.
 */
function probeSiteStorageAccess(): SiteStorageAccess {
  try {
    window.localStorage.getItem(STORAGE_ACCESS_PROBE_KEY);
    return { accessible: true };
  } catch (storageAccessDenial) {
    return { accessible: false, denial: storageAccessDenial };
  }
}

/**
 * Loads Clerk exactly once and starts mirroring its session into
 * {@link clerkAuthentication}. Failures surface as an error toast and a
 * rethrown error — the chat itself works unauthenticated, but a broken auth
 * configuration must never be silent ([RC1f]). Environments where auth cannot
 * exist (no publishable key in the build, or a browser that denies site
 * storage) are deliberate disabled states, not failures: the function returns
 * quietly and auth controls stay hidden.
 *
 * @throws Error when `Clerk.load()` rejects (misconfigured key, network or SDK failure).
 */
export async function loadClerkAuthentication(): Promise<void> {
  if (clerkClient) {
    return;
  }
  const publishableKey = import.meta.env.VITE_CLERK_PUBLISHABLE_KEY;
  if (!publishableKey) {
    // Deliberate per-deployment switch, not an error: production builds omit
    // VITE_CLERK_PUBLISHABLE_KEY until Clerk launches there, so auth controls
    // stay hidden. Dev deployments and local .env.local provide the key.
    console.info("Clerk authentication disabled: no VITE_CLERK_PUBLISHABLE_KEY in this build.");
    clerkAuthentication.phase = "disabled";
    return;
  }
  const siteStorageAccess = probeSiteStorageAccess();
  if (!siteStorageAccess.accessible) {
    // Environment gate, not an error: sign-in cannot work where the browser
    // denies site storage, so auth controls stay hidden — same deliberate
    // disabled state as a build without a publishable key. Skipping here also
    // avoids downloading the SDK chunks on such devices.
    console.info(
      "Clerk authentication disabled: this browser denies site storage access.",
      siteStorageAccess.denial,
    );
    clerkAuthentication.phase = "disabled";
    return;
  }
  // Dynamic imports keep the ~700 kB Clerk SDK out of the first-paint chunk;
  // auth controls appear once loaded, the chat itself never waits on them.
  // The npm ESM build of clerk-js ships without prebuilt components, so the
  // `ui` module from @clerk/ui must be passed explicitly (ClerkOptions.ui).
  const [{ Clerk: ClerkConstructor }, { ui: clerkPrebuiltUi }, { dark: clerkDarkBaseTheme }] =
    await Promise.all([import("@clerk/clerk-js"), import("@clerk/ui"), import("@clerk/ui/themes")]);
  const loadingClient = new ClerkConstructor(publishableKey);
  const nativeOAuth = window.javaChatNativeOAuth;
  try {
    await loadingClient.load({
      ui: clerkPrebuiltUi,
      appearance: warmPrecisionAppearance(clerkDarkBaseTheme),
      __internal_oauthTransport: nativeOAuth
        ? {
            getRedirectUrl: () => nativeOAuth.getRedirectUrl(),
            open: (authorizationURL: URL) => nativeOAuth.open(authorizationURL.toString()),
          }
        : undefined,
    });
  } catch (loadFailure) {
    clerkAuthentication.phase = "failed";
    pushToast("Sign-in is unavailable: Clerk failed to load.", {
      detail: loadFailure instanceof Error ? loadFailure.message : String(loadFailure),
    });
    throw loadFailure;
  }
  clerkClient = loadingClient;
  clerkClient.addListener((clerkResources) => {
    clerkAuthentication.signedInUser = clerkResources.user ?? null;
  });
  clerkAuthentication.signedInUser = clerkClient.user ?? null;
  clerkAuthentication.phase = "ready";
}

/** Opens Clerk's hosted sign-in modal. */
export function openSignIn(): void {
  requireLoadedClerkClient().openSignIn();
}

/** Opens Clerk's hosted sign-up modal. */
export function openSignUp(): void {
  requireLoadedClerkClient().openSignUp();
}

/**
 * Creates a personal CLI key for the currently signed-in user.
 *
 * The explicit subject prevents an active organization from silently becoming
 * the key owner. Clerk returns the secret only from this creation call.
 */
export async function createCliApiKey(clientLabel: string): Promise<string> {
  const availabilityResponse = await fetch("/api/security/api-key-availability");
  if (!availabilityResponse.ok) {
    throw new Error("This JavaChat deployment cannot verify CLI API keys.");
  }
  const loadedClient = requireLoadedClerkClient();
  const signedInUser = loadedClient.user;
  if (!signedInUser) {
    throw new Error("Sign in before authorizing the JavaChat CLI.");
  }
  const createdApiKey = await loadedClient.apiKeys.create({
    name: clientLabel,
    subject: signedInUser.id,
    description: "JavaChat command-line client",
    secondsUntilExpiration: 60 * 60 * 24 * 30,
  });
  if (!createdApiKey.secret) {
    throw new Error("Clerk created an API key without returning its one-time secret.");
  }
  return createdApiKey.secret;
}

/**
 * Svelte attachment that mounts Clerk's prebuilt user button into the
 * attached element and unmounts it on teardown.
 *
 * Usage: `<div {@attach attachUserButton}></div>`.
 */
export function attachUserButton(userButtonHost: HTMLDivElement): () => void {
  const mountedClient = requireLoadedClerkClient();
  mountedClient.mountUserButton(userButtonHost);
  return () => {
    mountedClient.unmountUserButton(userButtonHost);
  };
}

/**
 * Renders Clerk's prebuilt auth surfaces (sign-in/sign-up modals, user button)
 * in the app's "Warm Precision" design system on top of Clerk's dark base
 * theme. Every color references the design token's CSS custom property, so
 * global.css stays the sole owner of the palette — Clerk derives hover and
 * alpha shades from these values with CSS color-mix at paint time.
 */
function warmPrecisionAppearance(darkBaseTheme: Appearance["theme"]): Appearance {
  return {
    theme: darkBaseTheme,
    options: {
      logoImageUrl: "/assets/javachat_cup_star_256.png",
    },
    variables: {
      fontFamily: "var(--font-sans)",
      borderRadius: "var(--radius-md)",
      colorPrimary: "var(--color-accent)",
      // Dedicated on-accent token: --color-text-primary is dark charcoal in
      // the light theme, which would fail WCAG AA on the accent button.
      colorPrimaryForeground: "var(--color-accent-foreground)",
      colorBackground: "var(--color-bg-secondary)",
      colorForeground: "var(--color-text-primary)",
      colorMutedForeground: "var(--color-text-tertiary)",
      // Cream neutral: Clerk's derived alpha borders/hovers then land in the
      // same rgba(255,252,247,…) family as the app's border tokens.
      colorNeutral: "var(--color-text-primary)",
      colorInput: "var(--color-bg-tertiary)",
      colorInputForeground: "var(--color-text-primary)",
      colorBorder: "var(--color-border-default)",
      colorRing: "var(--color-accent)",
      colorDanger: "var(--color-error)",
      colorSuccess: "var(--color-success)",
      colorWarning: "var(--color-warning)",
      // Same overlay as MobileChatDrawer's ::backdrop; without it the backdrop
      // derives from the cream colorNeutral and washes out the page.
      colorModalBackdrop: "rgba(0, 0, 0, 0.5)",
    },
  };
}

function requireLoadedClerkClient(): Clerk {
  if (!clerkClient) {
    throw new Error(
      "Clerk is not loaded; call loadClerkAuthentication() before using auth actions.",
    );
  }
  return clerkClient;
}
