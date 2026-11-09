import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 서버: REST와 WebSocket을 edge-gateway(:8080)로 넘긴다. 브라우저 입장에서는 같은 출처다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
})
