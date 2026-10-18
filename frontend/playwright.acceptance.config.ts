import {defineConfig} from '@playwright/test'
import base from './playwright.config'
const prefix=process.env.QA_RUN_PREFIX||'final-'

export default defineConfig({
  ...base,
  outputDir:`../docs/qa-20261017/${prefix}browser-artifacts`,
  use:{...base.use,baseURL:'http://127.0.0.1:4182',timezoneId:'Asia/Singapore'},
  reporter:[['list'],['json',{outputFile:`../docs/qa-20261017/${prefix}browser-results.json`}],['html',{outputFolder:`../docs/qa-20261017/${prefix}browser-report`,open:'never'}]],
  webServer:{
    command:'node node_modules/vite/bin/vite.js preview --config vite.acceptance.config.ts --host 127.0.0.1 --port 4182',
    url:'http://127.0.0.1:4182/api/health',reuseExistingServer:false,
  },
})
