import {test,expect,type Page,type APIRequestContext} from '@playwright/test'
import {randomUUID} from 'node:crypto'
import {writeFileSync} from 'node:fs'
import {artifact} from './artifacts'

async function credentials(request:APIRequestContext,username='admin'){
  const r=await request.post('/api/login',{data:{username,password:'Farm@2026'}});expect(r.status()).toBe(200);return {Authorization:'Bearer '+(await r.json()).token}
}
async function signIn(page:Page,username:string){await page.goto('/');await page.getByLabel('账号',{exact:true}).fill(username);await page.getByLabel('密码',{exact:true}).fill('Farm@2026');await page.getByRole('button',{name:'登录账本',exact:true}).click();await expect(page.locator('.app-layout')).toBeVisible()}
async function go(page:Page,name:string,width=1440){if(width<800)await page.getByRole('button',{name:'打开导航',exact:true}).click();await page.locator('.sidebar').getByRole('button',{name,exact:true}).click()}
async function fixture(request:APIRequestContext){
  const headers=await credentials(request),recorder=await credentials(request,'recorder'),suffix=randomUUID().slice(0,6)
  const save=async(kind:string,data:object)=>{const r=await request.post('/api/catalog/'+kind,{headers,data});expect(r.status()).toBe(200);return r.json()}
  const worker=await save('workers',{code:'CHANGE-'+suffix,name:'付款演示工人-'+suffix}),category=await save('categories',{name:'新流程类别-'+suffix}),product=await save('products',{name:'新流程番茄-'+suffix,categoryId:category.id})
  const boot=await(await request.get('/api/bootstrap',{headers})).json(),date=boot.today,month=date.slice(0,7)
  expect((await request.post('/api/rules',{headers,data:{productId:product.id,effectiveDate:month+'-01',scope:'MONTH',reason:'独立回归数据',rule:{mode:'SEGMENTS',stages:[{key:'main',label:'采摘筐数',type:'PIECE',rate:'3'}]}}})).status()).toBe(200)
  return {headers,recorder,suffix,save,worker,category,product,date,month}
}

for(const width of [1440,390])test(`记录员更正、老板直接补付及历史快照保护 ${width}px`,async({page,request})=>{
  test.setTimeout(120000);await page.setViewportSize({width,height:1000})
  const f=await fixture(request),recordId=randomUUID(),errors:string[]=[];page.on('pageerror',e=>errors.push(e.message))
  expect((await request.post('/api/records/operate',{headers:f.recorder,data:{operationId:randomUUID(),action:'CREATE',recordId,workerId:f.worker.id,workDate:f.date,period:'AM',productId:f.product.id,inputs:{quantities:{main:'5'},weightUnit:'kg'},note:'',reason:'',version:null}})).status()).toBe(200)
  await signIn(page,'admin');await go(page,'月度工资',width);await page.getByLabel('工资查询',{exact:true}).fill(f.worker.code)
  const row=page.locator('.payroll-table tbody tr');await expect(row).toHaveCount(1);await expect(row.locator('td').nth(3)).toHaveText('15.00');await expect(row.locator('td').nth(4)).toHaveText('15.00')
  await expect(page.getByRole('button',{name:'确认月度结算',exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'确认补结算',exact:true})).toHaveCount(0)
  await row.getByRole('button',{name:'登记付款',exact:true}).click();let dialog=page.getByRole('dialog',{name:'登记付款'});await dialog.getByLabel('本次实际支付（元）',{exact:true}).fill('10');await dialog.getByRole('button',{name:'保存付款登记',exact:true}).click();await expect(dialog).toBeHidden();await expect(row.locator('td').nth(4)).toHaveText('5.00')
  const original=await(await request.get('/api/payroll?month='+f.month,{headers:f.headers})).json()
  let initial:any,snapshots:any
  for(const batch of original.settlements){const lines=await(await request.get('/api/settlements/'+batch.id+'/lines',{headers:f.headers})).json();if(lines.some((l:any)=>l.worker_id===f.worker.id)){initial=batch;snapshots=lines;break}}
  expect(initial).toBeDefined()
  await page.locator('.header-logout').click();await signIn(page,'recorder');await go(page,'采摘账本',width);await page.getByLabel('搜索采摘记录',{exact:true}).fill(f.worker.code)
  const record=page.locator('.ledger-table tbody tr');await expect(record).toHaveCount(1);await record.getByRole('button',{name:'更正已结算记录',exact:true}).click();dialog=page.getByRole('dialog');await dialog.getByLabel('采摘筐数',{exact:true}).fill('7');await dialog.getByLabel('修改原因',{exact:true}).fill('现场核实，原记录少记两筐');await dialog.getByRole('button',{name:'保存记录',exact:true}).click();await expect(dialog).toBeHidden();await expect(record).toContainText('7 筐');await expect(record).toContainText('21.00')
  await page.screenshot({path:artifact(`changes-01-recorder-corrected-${width}.png`),fullPage:true})
  await page.locator('.header-logout').click();await signIn(page,'admin');await go(page,'月度工资',width);await page.getByLabel('工资查询',{exact:true}).fill(f.worker.code)
  await expect(row).toHaveCount(1);await expect(row.locator('td').nth(3)).toHaveText('21.00');await expect(row.locator('td').nth(4)).toHaveText('11.00')
  await page.screenshot({path:artifact(`changes-02-boss-unpaid-${width}.png`),fullPage:true})
  await row.getByRole('button',{name:'登记付款',exact:true}).click();dialog=page.getByRole('dialog',{name:'登记付款'});await expect(dialog.getByLabel('本次实际支付（元）',{exact:true})).toHaveValue('11');await dialog.getByLabel('付款备注',{exact:true}).fill('补付更正后的剩余工资');await dialog.getByRole('button',{name:'保存付款登记',exact:true}).click();await expect(dialog).toBeHidden();await expect(row.locator('td').nth(3)).toHaveText('21.00');await expect(row.locator('td').nth(4)).toHaveText('0.00')
  await page.screenshot({path:artifact(`changes-03-boss-paid-${width}.png`),fullPage:true})
  expect(await(await request.get('/api/settlements/'+initial.id+'/lines',{headers:f.headers})).json()).toEqual(snapshots)
  const final=await(await request.get('/api/payroll?month='+f.month,{headers:f.headers})).json(),wage=final.workers.find((w:any)=>w.workerId===f.worker.id)
  expect(wage.confirmed).toBe(21);expect(wage.paid).toBe(21);expect(wage.unpaid).toBe(0)
  expect((await request.post('/api/records/operate',{headers:f.recorder,data:{operationId:randomUUID(),action:'UPDATE',recordId,workerId:f.worker.id,workDate:f.date,period:'AM',productId:f.product.id,inputs:{quantities:{main:'3'},weightUnit:'kg'},note:'',reason:'已付后复核减量',version:2,entry:{mode:'CORRECT',groupId:'',plotId:'',inputs:{quantities:{main:'3'},weightUnit:'kg'}}}})).status()).toBe(200)
  await page.getByRole('button',{name:'刷新数据',exact:true}).click();await expect(row.locator('td').nth(3)).toHaveText('9.00');await expect(row.locator('td').nth(4)).toContainText('-12.00');await expect(row).toContainText('已多付 12.00 元，需线下处理');await expect(row.getByRole('button',{name:'登记付款',exact:true})).toBeDisabled()

  writeFileSync(artifact(`changes-payment-proof-${width}.json`),JSON.stringify({worker:f.worker,recordId,before:{confirmed:15,firstPayment:10},afterCorrection:{confirmed:21,unpaid:11},afterSecondPayment:wage,afterReduction:{confirmed:9,unpaid:-12,blockedFurtherPayment:true},originalSnapshotUnchanged:true},null,2))
  expect(errors).toEqual([])
})

test('修改规则可直接发布，记录员维护并选择毛重筐重，净重只读',async({page,request})=>{
  const f=await fixture(request);await signIn(page,'admin');await go(page,'基础资料');await page.getByRole('button',{name:'产品与计费',exact:true}).click();await page.getByLabel('查询关键字',{exact:true}).fill(f.product.name)
  await expect(page.locator('tbody tr')).toHaveCount(1);await page.getByRole('button',{name:'配置计费规则',exact:true}).click();let editor=page.getByRole('dialog')
  await editor.getByLabel('单价（元/筐）',{exact:true}).fill('4');await editor.getByLabel('调整原因',{exact:true}).fill('无需试算直接发布');await expect(editor.getByRole('button',{name:'确认并发布规则',exact:true})).toBeEnabled();await editor.getByRole('button',{name:'确认并发布规则',exact:true}).click();await expect(editor).toBeHidden()
  const gross=await f.save('grossWeights',{name:'毛重26-'+f.suffix,weightKg:'26'}),basket=await f.save('baskets',{name:'单筐2-'+f.suffix,weightKg:'2'})
  const boot=await(await request.get('/api/bootstrap',{headers:f.headers})).json(),weightProduct=boot.products.find((p:any)=>p.name==='小白菜')
  await page.locator('.header-logout').click();await signIn(page,'recorder');await go(page,'重量配置');await page.getByRole('button',{name:'新增毛重配置',exact:true}).click();editor=page.getByRole('dialog');await editor.getByLabel('名称',{exact:true}).fill('记录员毛重-'+f.suffix);await editor.getByLabel('毛重（公斤）',{exact:true}).fill('32');await editor.getByRole('button',{name:'保存资料',exact:true}).click();await expect(editor).toBeHidden()
  await go(page,'采摘账本');await page.getByRole('button',{name:'记一笔采摘',exact:true}).click();editor=page.getByRole('dialog');await editor.getByLabel('工人',{exact:true}).selectOption(f.worker.id);await editor.getByLabel('产品',{exact:true}).selectOption(weightProduct.id);await editor.getByLabel('采摘重量：按毛重扣筐重',{exact:true}).check();await editor.getByLabel('毛重配置',{exact:true}).selectOption(gross.id);await editor.getByLabel('筐型',{exact:true}).selectOption(basket.id);await editor.getByLabel('实际容器数量',{exact:true}).fill('3')
  await expect(editor.getByLabel('实际重量',{exact:true})).toHaveValue('20');await expect(editor.getByLabel('实际重量',{exact:true})).toHaveAttribute('readonly','')
  const actual=await editor.getByLabel('实际重量',{exact:true}).boundingBox(),card=await editor.locator('.tare-card').boundingBox();expect(actual!.y).toBeGreaterThan(card!.y+card!.height)
  await page.screenshot({path:artifact('changes-weight-configured-net.png'),fullPage:true});await editor.getByRole('button',{name:'保存记录',exact:true}).click();await expect(editor).toBeHidden()
  const records=await(await request.get('/api/records?month='+f.month,{headers:f.headers})).json();expect(records.find((r:any)=>r.worker_id===f.worker.id).quote.parts[0].quantity).toBe(20)
})

test('更正归属只移动所选组成，查询分页可切换和过滤',async({page,request})=>{
  const f=await fixture(request),ga=await f.save('groups',{name:'原组-'+f.suffix}),gb=await f.save('groups',{name:'其他组-'+f.suffix}),gc=await f.save('groups',{name:'新组-'+f.suffix}),a=await f.save('plots',{name:'原棚-'+f.suffix}),b=await f.save('plots',{name:'其他棚-'+f.suffix}),c=await f.save('plots',{name:'新棚-'+f.suffix}),id=randomUUID()
  for(const [version,group,plot,quantity] of [[null,ga.id,a.id,'3'],[1,gb.id,b.id,'2']] as const){expect((await request.post('/api/records/operate',{headers:f.recorder,data:{operationId:randomUUID(),action:version===null?'CREATE':'UPDATE',recordId:id,workerId:f.worker.id,workDate:f.date,period:'AM',productId:f.product.id,version,reason:'',note:'',inputs:{quantities:{main:quantity},weightUnit:'kg'},entry:{mode:'ADD',groupId:group,plotId:plot,inputs:{quantities:{main:quantity},weightUnit:'kg'}}}})).status()).toBe(200)}
  await signIn(page,'recorder');await go(page,'采摘账本');await page.getByLabel('搜索采摘记录',{exact:true}).fill(f.worker.code);const row=page.locator('.ledger-table tbody tr');await expect(row).toHaveCount(1);await row.getByRole('button',{name:'更正地块',exact:true}).click();const editor=page.getByRole('dialog');await editor.getByLabel('原数量组成',{exact:true}).selectOption(JSON.stringify([ga.id,a.id]));await editor.getByLabel('本次所属班组',{exact:true}).selectOption(gc.id);await editor.getByLabel('本次地块',{exact:true}).selectOption(c.id);await expect(editor.getByLabel('正确地块',{exact:true})).toHaveCount(0);await editor.getByLabel('修改原因',{exact:true}).fill('只移动原组三筐');await editor.getByRole('button',{name:'保存记录',exact:true}).click();await expect(editor).toBeHidden();await expect(row).toContainText('5 筐');await expect(row).toContainText(gc.name);await expect(row).toContainText(gb.name)
  await page.locator('.header-logout').click();await signIn(page,'admin');const pageWorkers:any[]=[];for(let i=0;i<22;i++)pageWorkers.push(await f.save('workers',{code:`PAGING-${f.suffix}-${String(i).padStart(2,'0')}`,name:`分页工人-${f.suffix}-${i}`}))
  await go(page,'基础资料');await page.getByLabel('查询关键字',{exact:true}).fill('PAGING-'+f.suffix);await expect(page.locator('tbody tr')).toHaveCount(20);await expect(page.getByLabel('基础资料分页',{exact:true})).toContainText('共 22 条');await page.getByRole('button',{name:'下一页',exact:true}).click();await expect(page.locator('tbody tr')).toHaveCount(2)
  await page.getByLabel('查询关键字',{exact:true}).fill(`PAGING-${f.suffix}-21`);await expect(page.locator('tbody tr')).toHaveCount(1);await expect(page.getByLabel('基础资料分页',{exact:true})).toContainText('第 1 / 1 页')
  await page.screenshot({path:artifact('changes-pagination-query.png'),fullPage:true})
  for(const worker of pageWorkers){const inData={quantities:{main:'1'},weightUnit:'kg'};expect((await request.post('/api/records/operate',{headers:f.recorder,data:{operationId:randomUUID(),action:'CREATE',recordId:randomUUID(),workerId:worker.id,workDate:f.date,period:'AM',productId:f.product.id,version:null,reason:'',note:'',inputs:inData,entry:{mode:'ADD',groupId:ga.id,plotId:a.id,inputs:inData}}})).status()).toBe(200)}
  await go(page,'每日核对');await page.getByLabel('核对查询',{exact:true}).fill('paging-'+f.suffix);await page.getByLabel('班组',{exact:true}).selectOption(ga.id);await expect(page.locator('.daily-table tbody tr')).toHaveCount(20);await expect(page.getByLabel('每日核对分页',{exact:true})).toContainText('共 22 条')
  await page.evaluate(()=>{window.print=()=>{(window as any).__printedRows=document.querySelectorAll('.daily-table tbody tr').length;window.dispatchEvent(new Event('afterprint'))}})
  await expect(page.getByRole('button',{name:'打印核对表',exact:true})).toBeEnabled()
  const captured=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/daily/prints'&&r.request().method()==='POST');await page.getByRole('button',{name:'打印核对表',exact:true}).click();const printed=await(await captured).json();expect(printed.workers).toHaveLength(22);await expect.poll(()=>page.evaluate(()=>(window as any).__printedRows)).toBe(22)
  writeFileSync(artifact('changes-print-all-matches.json'),JSON.stringify({query:'paging-'+f.suffix,pageSize:20,matching:22,snapshotWorkers:printed.workers.length,renderedPrintRows:22},null,2))

})
