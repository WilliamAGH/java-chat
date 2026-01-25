import { defineConfig } from 'vitest/config'
import { svelte } from '@sveltejs/vite-plugin-svelte'

export default defineConfig({
  plugins: [svelte({ hot: !process.env.VITEST })],
  // Vitest runs modules through Vite's SSR pipeline by default, which can cause
  // conditional exports to resolve Svelte's server entry (where `mount()` is unavailable).
  // Force browser conditions so component tests can mount under jsdom.
  resolve: {
    conditions: ['module', 'browser', 'development']
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.{js,ts}'],
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/lib/**/*.{ts,svelte}'],
      exclude: ['src/lib/**/*.test.ts', 'src/test/**']
    }
  }
})
