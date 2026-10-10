import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// `vite dev` proxies the API to the backend so the browser sees one origin and
// the session cookie behaves exactly as it will in production behind nginx.
// `vite --mode mock` (npm run dev:mock) serves fixture data instead, no backend needed.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: { outDir: 'dist', sourcemap: false },
});
