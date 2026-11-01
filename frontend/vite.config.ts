import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxy: Record<string, any> = {
    '/api': { target: env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8123', changeOrigin: true, ws: true },
  }
  if (env.VITE_IMAGE_ORIGIN) {
    proxy['/image-proxy'] = { target: env.VITE_IMAGE_ORIGIN, changeOrigin: true,
      rewrite: (path: string) => path.replace(/^\/image-proxy/, '') }
  }
  return {
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: { host: '127.0.0.1', proxy },
  }
})
