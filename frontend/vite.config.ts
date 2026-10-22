import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
  test: { environment: 'jsdom', restoreMocks: true, clearMocks: true },
  build: { sourcemap: false, chunkSizeWarningLimit: 1200 },
})
