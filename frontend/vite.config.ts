import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

export default defineConfig({
  plugins: [svelte()],
  // Serve from root
  base: '/',
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8085',
        changeOrigin: true
      },
      '/actuator': {
        target: 'http://localhost:8085',
        changeOrigin: true
      }
    }
  },
  build: {
    // Build directly to Spring Boot static resources
    outDir: '../src/main/resources/static',
    emptyOutDir: false, // Don't delete favicons
    rollupOptions: {
      output: {
        manualChunks: {
          'highlight': [
            'highlight.js/lib/core',
            'highlight.js/lib/languages/java',
            'highlight.js/lib/languages/xml',
            'highlight.js/lib/languages/json',
            'highlight.js/lib/languages/bash'
          ],
          'markdown': ['marked']
        }
      }
    }
  }
})
