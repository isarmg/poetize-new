import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {compression} from 'vite-plugin-compression2'

export default defineConfig({
  base: '/im/',
  plugins: [
    vue(),
    compression({
      algorithms: ['gzip'],
      include: /\.(js|html|css)$/i,
      threshold: 10240,
      filename: '[path][base].gz',
      deleteOriginalAssets: false,
      skipIfLargerOrEqual: true
    })
  ],
  server: {
    port: 81,
    open: false
  },
  build: {
    sourcemap: false
  }
})
