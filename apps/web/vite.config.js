import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    open: true,
    // Everything under /api is forwarded to the API, so the browser only ever talks to one origin.
    // That is what keeps the session cookie first-party: served straight from :5173 to another
    // host it would be a third-party cookie, which Safari and Firefox block outright.
    //
    // GREENV_API_TARGET points this at the cloud API instead of the local stack.
    proxy: {
      '/api': {
        target: process.env.GREENV_API_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true,
        secure: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})
