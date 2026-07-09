import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

// In dev, proxy /api/* to the Go backend on :8000 (strips the /api prefix) so the browser makes
// same-origin requests — no CORS needed. In prod, serve the built dist/ from the Go server (WEB_DIR).
export default defineConfig({
  plugins: [svelte()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, ''),
      },
    },
  },
})
