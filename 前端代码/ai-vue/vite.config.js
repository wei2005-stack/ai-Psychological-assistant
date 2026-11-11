import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    proxy: {
      '/api': {
        // 本地联调统一指向本机后端（server.port=1236）。
        // SSE（/api/psychological-chat/stream）也走这条代理，
        // 代理默认不缓冲流式响应，逐字输出可以正常透传。
        target: 'http://localhost:1236',
        changeOrigin: true
      }
    }
  }
})
