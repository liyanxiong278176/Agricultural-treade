import {test,expect} from '@playwright/test'
import {spawn,execFile} from 'node:child_process'
import {createWriteStream,writeFileSync} from 'node:fs'
import {createServer} from 'node:net'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {randomUUID} from 'node:crypto'
import {artifact} from './artifacts'

test('开发入口断网刷新和关闭重开保留页面及待传记录',async({page,context,request})=>{
  test.setTimeout(120000)
  // Refuse an occupied dedicated port instead of reusing another service.
  await new Promise<void>((done,reject)=>{const probe=createServer();probe.once('error',reject);probe.listen(4184,'127.0.0.1',()=>probe.close(()=>done()))})
  const output=createWriteStream(artifact('development-server.log'))
  const frontendRoot=fileURLToPath(new URL('../',import.meta.url))
  const child=spawn(process.execPath,[resolve(frontendRoot,'node_modules/vite/bin/vite.js'),'--config','vite.acceptance.config.ts','--host','127.0.0.1','--port','4184','--strictPort'],{cwd:frontendRoot,env:{...process.env,FARM_HTTPS:'false',QA_BACKEND_PORT:process.env.QA_BACKEND_PORT||'8082'},windowsHide:true,stdio:['ignore','pipe','pipe']})
  child.stdout.pipe(output,{end:false});child.stderr.pipe(output,{end:false})
  const base='http://127.0.0.1:4184'
  try{
    await expect.poll(async()=>{try{return (await fetch(base+'/api/health',{signal:AbortSignal.timeout(1000)})).status}catch{return 0}},{timeout:30000}).toBe(200)
    const auth=await(await request.post('/api/login',{data:{username:'admin',password:'Farm@2026'}})).json(),headers={Authorization:'Bearer '+auth.token},suffix=randomUUID().slice(0,7)
    const created=await request.post('/api/catalog/workers',{headers,data:{code:'DEV-OFF-'+suffix,name:'开发离线工人-'+suffix}});expect(created.status()).toBe(200);const worker=await created.json(),boot=await(await request.get('/api/bootstrap',{headers})).json(),product=boot.products.find((p:any)=>p.name==='番茄')
    await page.goto(base);await page.getByLabel('账号',{exact:true}).fill('recorder');await page.getByLabel('密码',{exact:true}).fill('Farm@2026');await page.getByRole('button',{name:'登录账本',exact:true}).click();await expect(page.locator('.app-layout')).toBeVisible()
    await expect(page.getByText('离线页面已准备',{exact:true})).toBeVisible({timeout:30000});await page.waitForFunction(()=>!!navigator.serviceWorker.controller)
    writeFileSync(artifact('development-offline-cache.json'),JSON.stringify(await page.evaluate(async()=>{const result:any={};for(const name of await caches.keys()){const c=await caches.open(name);result[name]=(await c.keys()).map(r=>r.url)}return result}),null,2))
    await context.setOffline(true);await page.getByRole('button',{name:'记一笔采摘',exact:true}).first().click();const editor=page.getByRole('dialog');await editor.getByLabel('工人',{exact:true}).selectOption(worker.id);await editor.getByLabel('产品',{exact:true}).selectOption(product.id);await editor.getByLabel('采摘筐数',{exact:true}).fill('5.5');await editor.getByRole('button',{name:'保存记录',exact:true}).click();await expect(editor).toBeHidden()
    await page.reload();await expect(page.locator('.app-layout')).toBeVisible();await expect(page.locator('.offline-banner')).toBeVisible();await page.screenshot({path:artifact('development-offline-refreshed.png'),fullPage:true})
    await page.close();const reopened=await context.newPage();await reopened.goto(base);await expect(reopened.locator('.app-layout')).toBeVisible();await reopened.locator('.sidebar').getByRole('button',{name:/同步中心/}).click();await expect(reopened.locator('.sync-item')).toHaveCount(1)
    await reopened.screenshot({path:artifact('development-offline-reopened.png'),fullPage:true});await context.setOffline(false);await expect(reopened.locator('.sync-item')).toHaveCount(0,{timeout:30000})
    const rows=await(await request.get('/api/records?month='+boot.today.slice(0,7),{headers})).json(),matches=rows.filter((r:any)=>r.worker_id===worker.id);expect(matches).toHaveLength(1);expect(matches[0].quote.amount).toBe(16.5)
  }finally{
    await context.setOffline(false)
    if(child.pid){if(process.platform==='win32')await new Promise<void>(done=>execFile('taskkill',['/PID',String(child.pid),'/T','/F'],{windowsHide:true},()=>done()));else child.kill('SIGTERM')}
    output.end()
  }
})
