import {defineConfig} from '@playwright/test'
import base from './playwright.config'
import {resolve} from 'node:path'

if(!process.env.REGRESSION_REPORT_DIR)throw new Error('Run scripts/Test-Regression.ps1 to prepare the isolated backend and database.')
const report=resolve(process.env.REGRESSION_REPORT_DIR)
export default defineConfig({
  ...base,
  forbidOnly:true,
  retries:0,
  outputDir:resolve(report,'browser-artifacts'),
  use:{...base.use,baseURL:'http://127.0.0.1:4182',timezoneId:'Asia/Singapore'},
  reporter:[['list'],['json',{outputFile:resolve(report,'browser-results.json')}],['html',{outputFolder:resolve(report,'browser-report'),open:'never'}]],
  webServer:{
    command:'node node_modules/vite/bin/vite.js preview --config vite.acceptance.config.ts --host 127.0.0.1 --port 4182',
    url:'http://127.0.0.1:4182/api/health',
    reuseExistingServer:false,
    timeout:60000,
  },
})
