import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      // Proxy Java backend — avoids CORS issues in dev
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // Proxy Python inference engine
      '/inference': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/inference/, ''),
      },
    },
  },
})
