import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    cacheDir: `node_modules/.vite/${mode}-${env.VITE_API_MODE || 'mock'}`,
    plugins: [react()],
    server: {
      proxy: {
        '/api/v1': {
          target: env.API_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    test: { environment: 'node', include: ['src/**/*.test.ts'] },
  };
});
