import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {compression} from 'vite-plugin-compression2'

export default defineConfig({
  plugins: [
    vue(),
    compression({
      algorithms: ['gzip'],
      include: [/\.(js|mjs|html|css)$/],
      threshold: 10240,
      deleteOriginalAssets: false
    })
  ],
  server: {
    port: 80,
    https: false,
    open: false
  },
  build: {
    sourcemap: false
  }
})
