import { defineConfig, type HtmlTagDescriptor } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";

const SIMPLE_ANALYTICS_QUEUE_ORIGIN = "https://queue.simpleanalyticscdn.com";
const SIMPLE_ANALYTICS_HOSTNAME = "javachat.ai";
const SIMPLE_ANALYTICS_SCRIPT_URL = "https://scripts.simpleanalyticscdn.com/latest.js";
// Emitted as a standalone asset (not an inline script) so the backend's
// Content-Security-Policy can stay at script-src 'self' without hash pinning.
const SIMPLE_ANALYTICS_GUARD_ASSET_PATH = "assets/simple-analytics-guard.js";
const SIMPLE_ANALYTICS_RUNTIME_GUARD_SCRIPT = `;(function () {
  if (globalThis.location.hostname !== '${SIMPLE_ANALYTICS_HOSTNAME}') {
    return
  }

  var analyticsScript = document.createElement('script')
  analyticsScript.async = true
  analyticsScript.src = '${SIMPLE_ANALYTICS_SCRIPT_URL}'
  analyticsScript.setAttribute('data-hostname', '${SIMPLE_ANALYTICS_HOSTNAME}')
  document.head.appendChild(analyticsScript)
})()`;

// A Clerk publishable key is `pk_test_` or `pk_live_` followed by the base64 of
// its Frontend API host. Anything else (such as an unresolved `${{vault.…}}`
// secret-store reference passed through CI) builds cleanly but breaks sign-in
// in the browser, so the build rejects it.
const CLERK_PUBLISHABLE_KEY_PATTERN = /^pk_(test|live)_[A-Za-z0-9+/]+=*$/;

function buildSimpleAnalyticsTags(mode: string): HtmlTagDescriptor[] {
  if (mode !== "production") {
    return [];
  }

  const noScriptImageUrl = `${SIMPLE_ANALYTICS_QUEUE_ORIGIN}/noscript.gif?hostname=${encodeURIComponent(SIMPLE_ANALYTICS_HOSTNAME)}`;

  return [
    {
      tag: "script",
      attrs: {
        src: `/${SIMPLE_ANALYTICS_GUARD_ASSET_PATH}`,
        async: true,
      },
      injectTo: "body",
    },
    {
      tag: "noscript",
      children: [
        {
          tag: "img",
          attrs: {
            src: noScriptImageUrl,
            alt: "",
            referrerpolicy: "no-referrer-when-downgrade",
          },
        },
      ],
      injectTo: "body",
    },
  ];
}

export default defineConfig(({ mode }) => ({
  plugins: [
    svelte(),
    {
      name: "clerk-publishable-key-shape",
      configResolved(resolvedConfig) {
        const clerkPublishableKey: unknown = resolvedConfig.env.VITE_CLERK_PUBLISHABLE_KEY;
        if (
          typeof clerkPublishableKey === "string" &&
          clerkPublishableKey !== "" &&
          !CLERK_PUBLISHABLE_KEY_PATTERN.test(clerkPublishableKey)
        ) {
          throw new Error(
            "VITE_CLERK_PUBLISHABLE_KEY is set but is not a Clerk publishable key (expected pk_test_… or pk_live_…).",
          );
        }
      },
    },
    {
      name: "simple-analytics",
      generateBundle() {
        if (mode !== "production") {
          return;
        }
        this.emitFile({
          type: "asset",
          fileName: SIMPLE_ANALYTICS_GUARD_ASSET_PATH,
          source: SIMPLE_ANALYTICS_RUNTIME_GUARD_SCRIPT,
        });
      },
      transformIndexHtml() {
        return buildSimpleAnalyticsTags(mode);
      },
    },
  ],
  base: "/",
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8085",
        changeOrigin: true,
      },
      "/actuator": {
        target: "http://localhost:8085",
        changeOrigin: true,
      },
    },
  },
  build: {
    // Build directly to Spring Boot static resources
    outDir: "../src/main/resources/static",
    emptyOutDir: false, // Don't delete favicons
    rollupOptions: {
      output: {
        manualChunks: {
          highlight: [
            "highlight.js/lib/core",
            "highlight.js/lib/languages/java",
            "highlight.js/lib/languages/xml",
            "highlight.js/lib/languages/json",
            "highlight.js/lib/languages/bash",
          ],
          markdown: ["marked"],
        },
      },
    },
  },
}));
