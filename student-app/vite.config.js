import { defineConfig } from 'vite';
import uniModule from '@dcloudio/vite-plugin-uni';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Node.js ESM-CJS interop: default import 得到整个 exports 对象，需取 .default
const uni = uniModule.default || uniModule;

export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
});
