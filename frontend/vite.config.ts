import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

const SIMPLE_ANALYTICS_CDN = 'https://scripts.simpleanalyticscdn.com'

export default defineConfig(({ mode }) => ({
  plugins: [
    svelte(),
    {
      name: 'simple-analytics',
      transformIndexHtml() {
        const scriptFile = mode === 'development' ? 'latest.dev.js' : 'latest.js'
        return [
          {
            tag: 'script',
            attrs: {
              async: true,
              src: `${SIMPLE_ANALYTICS_CDN}/${scriptFile}`,
            },
            injectTo: 'head',
          },
        ]
      },
    },
  ],
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
}))
