import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { backendTarget } from './BackendConfig';

export default defineConfig({
  plugins: [vue()],
  server: {
    host: 'localhost',
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: backendTarget, changeOrigin: false } }
  }
});
