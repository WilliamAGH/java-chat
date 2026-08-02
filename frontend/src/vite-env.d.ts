/// <reference types="svelte" />
/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Clerk publishable key (pk_test_/pk_live_); injected by `clerk env pull` locally and Coolify in deployment. */
  readonly VITE_CLERK_PUBLISHABLE_KEY?: string;
}

interface JavaChatNativeOAuth {
  getRedirectUrl(): string;
  open(authorizationURL: string): Promise<{ callbackUrl: string }>;
}

interface Window {
  readonly javaChatNativeOAuth?: JavaChatNativeOAuth;
}
