import {defineConfig} from '@playwright/test'
import base from './playwright.config'

export default defineConfig({
  ...base,
  testMatch:'deletion.spec.ts',
  use:{...base.use,baseURL:'http://127.0.0.1:4181'},
  webServer:{
    command:'node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port 4181',
    url:'http://127.0.0.1:4181',
    reuseExistingServer:false,
  },
})
