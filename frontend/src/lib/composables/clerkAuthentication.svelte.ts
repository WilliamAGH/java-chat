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
  /** True once `Clerk.load()` resolved; auth controls stay hidden until then to avoid flicker. */
  isLoaded = $state(false);
  /**
   * Current signed-in user or null. `$state.raw` because the SDK replaces the
   * resource wholesale on every emission; deep proxying a class instance
   * would break its prototype methods.
   */
  signedInUser = $state.raw<ClerkSignedInUser | null>(null);
}

export const clerkAuthentication = new ClerkAuthenticationState();

let clerkClient: Clerk | null = null;

/**
 * Loads Clerk exactly once and starts mirroring its session into
 * {@link clerkAuthentication}. Failures surface as an error toast and a
 * rethrown error — the chat itself works unauthenticated, but a broken auth
 * configuration must never be silent ([RC1f]).
 *
 * @throws Error when the publishable key is absent or `Clerk.load()` fails.
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
  clerkAuthentication.isLoaded = true;
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
      colorPrimaryForeground: "var(--color-text-primary)",
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
