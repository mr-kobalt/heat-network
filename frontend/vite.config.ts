import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * В dev-режиме не подменяем отсутствующие файлы подложки на index.html,
 * иначе проверка наличия moscow.pmtiles через HEAD всегда «успешна».
 */
function basemapStaticPlugin(): Plugin {
  const basemapDir = fileURLToPath(new URL('./public/basemap', import.meta.url));
  return {
    name: 'basemap-no-spa-fallback',
    configureServer(server) {
      server.middlewares.use('/basemap', (request, response, next) => {
        const path = decodeURIComponent((request.url ?? '').split('?')[0]);
        if (existsSync(`${basemapDir}${path}`)) {
          next();
        } else {
          response.statusCode = 404;
          response.end('Not found');
        }
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), basemapStaticPlugin()],
  base: './',
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
  },
});
