import {defineConfig,mergeConfig} from 'vite'
import base from './vite.config'

// Dedicated QA backend and database; never proxy acceptance writes to the demo.
export default defineConfig(env=>mergeConfig(base(env),{
  server:{port:4184,https:undefined,proxy:{'/api':`http://127.0.0.1:${process.env.QA_BACKEND_PORT||'8082'}`}},
  preview:{port:4182,https:undefined,proxy:{'/api':`http://127.0.0.1:${process.env.QA_BACKEND_PORT||'8082'}`}},
}))
