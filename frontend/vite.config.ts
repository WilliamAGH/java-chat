import { defineConfig, type HtmlTagDescriptor } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";

const SIMPLE_ANALYTICS_QUEUE_ORIGIN = "https://queue.simpleanalyticscdn.com";
const SIMPLE_ANALYTICS_HOSTNAME = "javachat.ai";
const SIMPLE_ANALYTICS_SCRIPT_URL = "https://scripts.simpleanalyticscdn.com/latest.js";
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

function buildSimpleAnalyticsTags(mode: string): HtmlTagDescriptor[] {
  if (mode !== "production") {
    return [];
  }

  const noScriptImageUrl = `${SIMPLE_ANALYTICS_QUEUE_ORIGIN}/noscript.gif?hostname=${encodeURIComponent(SIMPLE_ANALYTICS_HOSTNAME)}`;

  return [
    {
      tag: "script",
      children: SIMPLE_ANALYTICS_RUNTIME_GUARD_SCRIPT,
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
      name: "simple-analytics",
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
