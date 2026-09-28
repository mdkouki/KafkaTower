import { defineConfig, loadEnv } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8089';

  return {
    plugins: [tailwindcss(), svelte()],
    server: {
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true
        }
      }
    },
    build: {
      outDir: '../backend/kafkatower-app/src/main/resources/static',
      emptyOutDir: true,
      rollupOptions: {
        output: {
          entryFileNames: 'assets/kafkatower-[hash].js',
          chunkFileNames: 'assets/kafkatower-[hash].js',
          assetFileNames: 'assets/kafkatower-[hash][extname]'
        }
      }
    }
  };
});
