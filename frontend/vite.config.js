import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          // 剥离浏览器携带的 Origin，使后端按同源反向代理请求处理，
          // 避免 Vite 自动切换端口（5173/5174...）时被 CORS 白名单拒绝
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin');
          });
        }
      }
    }
  }
});
