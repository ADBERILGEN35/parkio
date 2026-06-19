import path from 'node:path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const errorReportingProvider = process.env.VITE_FRONTEND_ERROR_REPORTING ?? 'disabled';
const sourcemap = errorReportingProvider === 'disabled' ? false : 'hidden';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    fs: {
      allow: [path.resolve(__dirname, '../..')],
    },
  },
  build: {
    sourcemap,
    chunkSizeWarningLimit: 650,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/maplibre-gl') || id.includes('node_modules/react-map-gl')) {
            return 'maplibre';
          }
          if (
            id.includes('node_modules/react/') ||
            id.includes('node_modules/react-dom/') ||
            id.includes('node_modules/react-router-dom/')
          ) {
            return 'react-vendor';
          }
          if (id.includes('node_modules/@tanstack/react-query') || id.includes('node_modules/zustand')) {
            return 'app-vendor';
          }
        },
      },
    },
  },
});
