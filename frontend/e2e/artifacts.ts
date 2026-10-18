import {mkdirSync} from 'node:fs'
import {resolve} from 'node:path'

// Keep standalone evidence outside Playwright's automatically cleaned outputDir.
export const evidenceDir=resolve(process.env.REGRESSION_REPORT_DIR||'../.local/e2e','evidence')
mkdirSync(evidenceDir,{recursive:true})
export const artifact=(name:string)=>resolve(evidenceDir,name)
