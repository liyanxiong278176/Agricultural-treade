import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
const https=process.env.FARM_HTTPS==='true'?{key:readFileSync(fileURLToPath(new URL('../.local/certs/server-key.pem',import.meta.url))),cert:readFileSync(fileURLToPath(new URL('../.local/certs/server.pem',import.meta.url)))}:undefined
export default defineConfig(({command})=>({
  plugins: [vue(),VitePWA({registerType:'prompt',devOptions:{enabled:true},includeAssets:['farm.svg'],manifest:{name:'田间账本 · 农场采摘管理',short_name:'田间账本',lang:'zh-CN',theme_color:'#245447',background_color:'#F6F8F3',display:'standalone',start_url:'/',icons:[{src:'/farm.svg',sizes:'any',type:'image/svg+xml',purpose:'any'}]},workbox:{clientsClaim:true,runtimeCaching:command==='serve'?[{urlPattern:({url})=>url.origin===self.location.origin&&!url.pathname.startsWith('/api/')&&!url.pathname.includes('sw.js'),handler:'NetworkFirst',options:{cacheName:'farm-dev-shell-v1',networkTimeoutSeconds:2,matchOptions:{ignoreVary:true}}}]:[],globPatterns:['**/*.{js,css,html,svg,woff2}'],maximumFileSizeToCacheInBytes:4000000,navigateFallback:'/index.html',navigateFallbackDenylist:[/^\/api/],cleanupOutdatedCaches:true}})],
  server:{port:5173,strictPort:true,https,proxy:{'/api':'http://127.0.0.1:8080'}},
  preview:{port:4173,strictPort:true,https,proxy:{'/api':'http://127.0.0.1:8080'}},
  build:{chunkSizeWarningLimit:1200}
}))
