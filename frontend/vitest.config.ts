import { defineConfig } from "vitest/config";
import { svelte } from "@sveltejs/vite-plugin-svelte";
import { svelteTesting } from "@testing-library/svelte/vite";

const COMPONENT_TEST_TIMEOUT_MS = 10_000;

export default defineConfig({
  plugins: [svelte({ hot: !process.env.VITEST }), svelteTesting()],
  server: {
    fs: {
      allow: [".."],
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    include: ["src/**/*.{test,spec}.{js,ts}"],
    setupFiles: ["./src/test/setup.ts"],
    testTimeout: COMPONENT_TEST_TIMEOUT_MS,
    coverage: {
      provider: "v8",
      reporter: ["text", "html"],
      include: ["src/lib/**/*.{ts,svelte}"],
      exclude: ["src/lib/**/*.test.ts", "src/test/**"],
    },
  },
});
