import {test,expect,type Page,type APIRequestContext} from '@playwright/test'
import {randomUUID} from 'node:crypto'
import {artifact} from './artifacts'

const today=()=>new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Singapore'})
async function fixture(request:APIRequestContext){
  const auth=await request.post('/api/login',{data:{username:'admin',password:'Farm@2026'}})
  const token=(await auth.json()).token,headers={Authorization:'Bearer '+token}
  const response=await request.post('/api/catalog/workers',{headers,data:{name:'网络验收'+randomUUID().slice(0,6),code:'QA-NET-'+randomUUID().slice(0,8),active:true}})
  expect(response.status()).toBe(200)
  const boot=await(await request.get('/api/bootstrap',{headers})).json()
  return {worker:await response.json(),product:boot.products.find((p:any)=>p.name==='番茄'),headers}
}
async function login(page:Page){
  await page.goto('/');await page.getByLabel('账号',{exact:true}).fill('recorder')
  await page.getByLabel('密码',{exact:true}).fill('Farm@2026')
  await page.getByRole('button',{name:'登录账本'}).click()
  await expect(page.locator('.stat').nth(3).locator('strong')).toHaveText(/[1-9]/)
}
async function entry(page:Page,worker:string,product:string){
  await page.getByRole('button',{name:'记一笔采摘',exact:true}).first().click()
  await page.getByLabel('工人',{exact:true}).selectOption(worker)
  await page.getByLabel('产品',{exact:true}).selectOption(product)
  await page.getByLabel('采摘筐数',{exact:true}).fill('5.5')
}
async function localQueue(page:Page){
  return page.evaluate(()=>new Promise<any[]>((resolve,reject)=>{
    const opening=indexedDB.open('farm-ledger-v1')
    opening.onerror=()=>reject(opening.error)
    opening.onsuccess=()=>{const db=opening.result;const get=db.transaction('queue').objectStore('queue').getAll();get.onsuccess=()=>{resolve(get.result);db.close()};get.onerror=()=>reject(get.error)}
  }))
}

test('NET-01 浏览器仍在线但API连接中断，原操作持久化后自动重试',async({page,request})=>{
  const {worker,product,headers}=await fixture(request);await login(page)
  await page.route('**/api/records/operate',route=>route.abort('connectionfailed'))
  await entry(page,worker.id,product.id)
  await page.getByRole('button',{name:'保存记录',exact:true}).click()
  await expect(page.locator('.toast')).toContainText('已保存在本机')
  expect(await page.evaluate(()=>navigator.onLine)).toBeTruthy()
  await expect.poll(async()=> (await localQueue(page)).length).toBe(1)
  const before=(await localQueue(page))[0]
  expect(before.state).toBe('pending');expect(before.attempted).toBeTruthy()
  await page.unroute('**/api/records/operate')
  await page.locator('.sidebar').getByRole('button',{name:/同步中心/}).click()
  await page.getByRole('button',{name:'立即同步'}).click()
  await expect.poll(async()=> (await localQueue(page)).length).toBe(0)
  const rows=await(await request.get('/api/records?month='+today().slice(0,7),{headers})).json()
  expect(rows.filter((r:any)=>r.worker_id===worker.id)).toHaveLength(1)
  expect(rows.find((r:any)=>r.id===before.operation.recordId).quote.amount).toBe(16.5)
})

test('NET-02 服务端已入账但响应丢失，原UUID补传仅产生一次工资和审计',async({page,request})=>{
  const {worker,product,headers}=await fixture(request);await login(page)
  let operationId='',recordId='',persisted=false
  await page.route('**/api/records/operate',async route=>{
    const op=route.request().postDataJSON();operationId=op.operationId;recordId=op.recordId
    const response=await route.fetch();expect(response.status()).toBe(200)
    persisted=(await response.json()).status==='ACCEPTED'
    await route.abort('connectionfailed')
  })
  await entry(page,worker.id,product.id);await page.getByRole('button',{name:'保存记录',exact:true}).click()
  await expect(page.locator('.toast')).toContainText('已保存在本机');expect(persisted).toBeTruthy()
  expect((await localQueue(page))[0].operationId).toBe(operationId)
  await page.unroute('**/api/records/operate')
  await page.locator('.sidebar').getByRole('button',{name:/同步中心/}).click()
  await page.getByRole('button',{name:'立即同步'}).click()
  await expect.poll(async()=> (await localQueue(page)).length).toBe(0)
  const rows=await(await request.get('/api/records?month='+today().slice(0,7),{headers})).json()
  expect(rows.filter((r:any)=>r.worker_id===worker.id)).toHaveLength(1)
  expect(rows.find((r:any)=>r.id===recordId).version).toBe(1)
  const logs=await(await request.get('/api/audit',{headers})).json()
  expect(logs.filter((l:any)=>l.entity_id===recordId&&l.action==='CREATE')).toHaveLength(1)
})

test('NET-03 真断网后关闭页面并重开，IndexedDB与离线页面保留且恢复自动上传',async({page,context,request})=>{
  const {worker,product,headers}=await fixture(request);await login(page)
  await page.evaluate(()=>navigator.serviceWorker.ready.then(()=>true))
  if(!await page.evaluate(()=>!!navigator.serviceWorker.controller))await page.reload()
  await page.waitForFunction(()=>!!navigator.serviceWorker.controller)
  await context.setOffline(true)
  await entry(page,worker.id,product.id);await page.getByRole('button',{name:'保存记录',exact:true}).click()
  await expect(page.locator('.toast')).toContainText('已保存在本机')
  const before=(await localQueue(page))[0]
  await page.close()
  const reopened=await context.newPage();await reopened.goto('/')
  await expect(reopened.locator('.offline-banner')).toContainText('当前网络异常')
  expect((await localQueue(reopened))[0].operationId).toBe(before.operationId)
  await reopened.screenshot({path:artifact('offline-reopened.png'),fullPage:true})
  await context.setOffline(false)
  await expect.poll(async()=> (await localQueue(reopened)).length,{timeout:30000}).toBe(0)
  const rows=await(await request.get('/api/records?month='+today().slice(0,7),{headers})).json()
  expect(rows.filter((r:any)=>r.worker_id===worker.id)).toHaveLength(1)
})

test('SYS-01 手机手工重量录入与后台账本联通、统计切换和页面错误检查',async({page,request})=>{
  const {worker,headers}=await fixture(request)
  const boot=await(await request.get('/api/bootstrap',{headers})).json(),product=boot.products.find((p:any)=>p.name==='小白菜')
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message))
  await page.setViewportSize({width:390,height:844});await login(page)
  await page.getByRole('button',{name:'记一笔采摘',exact:true}).first().click()
  await page.getByLabel('工人',{exact:true}).selectOption(worker.id);await page.getByLabel('产品',{exact:true}).selectOption(product.id)
  await page.getByLabel('重量单位').selectOption('jin')
  await page.getByLabel('采摘重量',{exact:true}).fill('20')
  await page.screenshot({path:artifact('mobile-weight-input.png'),fullPage:true})
  await page.getByRole('button',{name:'保存记录',exact:true}).click()
  await expect(page.locator('.toast')).toHaveText('保存成功')
  const rows=await(await request.get('/api/records?month='+today().slice(0,7),{headers})).json()
  const row=rows.find((r:any)=>r.worker_id===worker.id);expect(row.quote.amount).toBe(5);expect(row.quote.parts[0].quantity).toBe(10)
  await page.getByRole('button',{name:'重量 / 公斤',exact:true}).click()
  await expect(page.getByRole('heading',{name:'产品采摘重量排行'})).toBeVisible()
  expect(await page.locator('canvas').count()).toBe(3)
  await page.getByRole('button',{name:'计件 / 筐',exact:true}).click()
  await expect(page.getByRole('heading',{name:'每日采摘筐数趋势'})).toBeVisible()
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
  expect(errors).toEqual([])
})

for(const failure of ['503','timeout']){
  test(`NET-04 ${failure} 后记录保留、连接恢复按原操作自动同步`,async({page,request})=>{
    const {worker,product,headers}=await fixture(request);await login(page)
    await page.route('**/api/sync',route=>route.abort('connectionfailed'))
    await page.route('**/api/records/operate',async route=>{
      if(failure==='503')await route.fulfill({status:503,json:{message:'QA temporary unavailable'}})
      else{
        await new Promise(resolve=>setTimeout(resolve,13000))
        await route.abort('timedout').catch(()=>{})
      }
    })
    await entry(page,worker.id,product.id);await page.getByRole('button',{name:'保存记录',exact:true}).click()
    await expect(page.getByRole('dialog',{name:'记一笔采摘'})).toBeHidden({timeout:18000})
    await expect.poll(async()=> (await localQueue(page)).length).toBe(1)
    const pending=(await localQueue(page))[0];expect(pending.state).toBe('pending')
    await page.unroute('**/api/sync');await page.unroute('**/api/records/operate')
    // Exercise the application's periodic retry without an online event or manual click.
    await expect.poll(async()=> (await localQueue(page)).length,{timeout:25000}).toBe(0)
    const rows=await(await request.get('/api/records?month='+today().slice(0,7),{headers})).json()
    expect(rows.filter((r:any)=>r.worker_id===worker.id)).toHaveLength(1)
    expect(rows.find((r:any)=>r.id===pending.operation.recordId).quote.amount).toBe(16.5)
  })
}
