import {test,expect} from '@playwright/test'
import {randomUUID} from 'node:crypto'

for(const width of [1440,390])test(`记录员跨账号更正历史已结算记录，原快照不变 ${width}px`,async({page,request})=>{
  const login=async(username:string)=>{
    const response=await request.post('/api/login',{data:{username,password:'Farm@2026'}})
    expect(response.status()).toBe(200)
    return {Authorization:'Bearer '+(await response.json()).token}
  }
  const admin=await login('admin'),author=await login('recorder2'),suffix=randomUUID().slice(0,8)
  const save=async(kind:string,data:object)=>{
    const response=await request.post('/api/catalog/'+kind,{headers:admin,data})
    expect(response.status()).toBe(200);return response.json()
  }
  const worker=await save('workers',{code:'REG-HIST-'+suffix,name:'历史更正-'+suffix})
  const category=await save('categories',{name:'历史回归类别-'+suffix})
  const product=await save('products',{categoryId:category.id,name:'历史回归产品-'+suffix})
  const month='2024-09',workDate=month+'-15'
  expect((await request.post('/api/rules',{headers:admin,data:{productId:product.id,effectiveDate:month+'-01',scope:'MONTH',reason:'回归专用历史计价',rule:{mode:'SEGMENTS',stages:[{key:'main',label:'采摘筐数',type:'PIECE',rate:'3'}]}}})).status()).toBe(200)
  const recordId=randomUUID()
  expect((await request.post('/api/records/operate',{headers:author,data:{operationId:randomUUID(),action:'CREATE',recordId,workerId:worker.id,productId:product.id,workDate,period:'AM',inputs:{quantities:{main:'5'},weightUnit:'kg'},note:'历史漏记补录',reason:'',version:null}})).status()).toBe(200)
  const confirmation=await request.post('/api/payroll/'+month+'/confirm',{headers:admin})
  expect(confirmation.status()).toBe(200);const settlement=(await confirmation.json()).id
  const snapshots=async()=>(await request.get('/api/settlements/'+settlement+'/lines',{headers:admin})).json()
  const original=await snapshots()
  await page.setViewportSize({width,height:900});await page.goto('/')
  await page.getByLabel('账号',{exact:true}).fill('recorder');await page.getByLabel('密码',{exact:true}).fill('Farm@2026');await page.getByRole('button',{name:'登录账本'}).click()
  await expect(page.locator('.app-layout')).toBeVisible()
  await page.getByLabel('账本月份').fill(month)
  await page.locator(width<800?'.bottom-nav':'.sidebar').getByRole('button',{name:'采摘账本',exact:true}).click()
  await page.getByLabel('搜索采摘记录').fill(worker.name)
  const row=page.locator('.ledger-table tbody tr');await expect(row).toHaveCount(1);await expect(row).toContainText('已结算')
  await expect(row.getByRole('button',{name:'删除记录',exact:true})).toBeDisabled()
  await row.getByRole('button',{name:'更正已结算记录',exact:true}).click()
  const editor=page.getByRole('dialog');await expect(editor).toContainText('原账单保留')
  await editor.getByLabel('采摘筐数',{exact:true}).fill('7')
  await expect(editor.getByLabel('修改原因',{exact:true})).toHaveAttribute('required','')
  await editor.getByLabel('修改原因',{exact:true}).fill('跨记录员核实历史少记两筐')
  await editor.getByRole('button',{name:'保存记录',exact:true}).click();await expect(editor).toBeHidden()
  await expect(row).toContainText('7 筐');expect(await snapshots()).toEqual(original)
  const payroll=await(await request.get('/api/payroll?month='+month,{headers:admin})).json()
  const wage=payroll.workers.find((w:any)=>w.workerId===worker.id)
  expect(wage.earned).toBe(21);expect(wage.settled).toBe(15);expect(wage.pending).toBe(6)
  const history=await(await request.get('/api/records/'+recordId+'/history',{headers:author})).json()
  expect(history.some((entry:any)=>entry.reason==='跨记录员核实历史少记两筐'&&entry.actor_name==='记录员小林')).toBeTruthy()
  expect((await request.post('/api/payroll/'+month+'/confirm',{headers:author})).status()).toBe(403)
  expect((await request.post('/api/payments',{headers:author,data:{id:randomUUID(),workerId:worker.id,month,amount:'1',method:'现金'}})).status()).toBe(403)
})
