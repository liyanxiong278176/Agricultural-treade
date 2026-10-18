import {test,expect,type Page,type APIRequestContext} from '@playwright/test'
import {randomUUID} from 'node:crypto'
import {mkdirSync} from 'node:fs'
import {evidenceDir} from './artifacts'
const output=evidenceDir
mkdirSync(output,{recursive:true})
async function fixture(request:APIRequestContext){
  const auth=await(await request.post('/api/login',{data:{username:'admin',password:'Farm@2026'}})).json()
  const headers={Authorization:'Bearer '+auth.token},suffix=randomUUID().slice(0,7)
  async function save(kind:string,data:object){const r=await request.post('/api/catalog/'+kind,{headers,data});expect(r.ok(),await r.text()).toBeTruthy();return r.json()}
  const group=await save('groups',{name:'扩展组-'+suffix}),a=await save('plots',{name:'一号棚-'+suffix}),b=await save('plots',{name:'二号棚-'+suffix}),worker=await save('workers',{name:'扩展工人-'+suffix,code:'E2E-EXT-'+suffix,groupId:group.id})
  const boot=await(await request.get('/api/bootstrap',{headers})).json()
  return {headers,group,a,b,worker,product:boot.products.find((p:any)=>p.name==='小白菜'),date:boot.today}
}
async function login(page:Page){await page.goto('/');await page.getByLabel('账号',{exact:true}).fill('recorder');await page.getByLabel('密码',{exact:true}).fill('Farm@2026');await page.getByRole('button',{name:'登录账本'}).click();await expect(page.locator('.app-layout')).toBeVisible()}
async function go(page:Page,name:string,width:number){if(width<800)await page.getByRole('button',{name:'打开导航',exact:true}).click();await page.locator('.sidebar').getByRole('button',{name,exact:true}).click()}
for(const width of [1440,390])test(`地块、导入和打印快照完整流程 ${width}px`,async({page,request,context})=>{
  test.setTimeout(120000);page.setDefaultTimeout(12000);await page.setViewportSize({width,height:1000})
  const f=await fixture(request),errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));await login(page)
  await expect(page.locator('.sidebar').getByRole('button',{name:'每日派工',exact:true})).toHaveCount(0)
  await go(page,'采摘账本',width);await page.getByRole('button',{name:'记一笔采摘',exact:true}).first().click();let dialog=page.getByRole('dialog')
  await dialog.getByLabel('工人',{exact:true}).selectOption(f.worker.id);await dialog.getByLabel('产品',{exact:true}).selectOption(f.product.id);await dialog.getByRole('button',{name:'上午',exact:true}).click();await dialog.getByLabel('本次所属班组',{exact:true}).selectOption(f.group.id);await dialog.getByLabel('本次地块',{exact:true}).selectOption(f.a.id);await dialog.getByLabel('采摘重量',{exact:true}).fill('20');await dialog.getByRole('button',{name:'保存记录',exact:true}).click();await expect(dialog).toBeHidden()
  await page.getByLabel('搜索采摘记录').fill(f.worker.name);let row=page.locator('.ledger-table tbody tr');await expect(row).toHaveCount(1);await expect(row).toContainText('20 公斤')
  await row.getByRole('button',{name:'增加采摘量',exact:true}).click();dialog=page.getByRole('dialog');await dialog.getByLabel('本次地块',{exact:true}).selectOption(f.b.id);await dialog.getByLabel('采摘重量',{exact:true}).fill('30');await dialog.getByRole('button',{name:'保存记录',exact:true}).click();await expect(dialog).toBeHidden();await expect(row).toContainText('50 公斤');await expect(row).toHaveCount(1)
  await go(page,'地块人工成本',width);await page.getByLabel('成本查询',{exact:true}).fill(f.worker.code.slice('E2E-EXT-'.length));await expect(page.locator('tbody tr')).toHaveCount(2);await expect(page.locator('tbody')).toContainText(f.a.name);await expect(page.locator('tbody')).toContainText(f.b.name);await page.screenshot({path:output+`/costs-${width}.png`,fullPage:true})
  await go(page,'每日核对',width);await page.getByRole('combobox',{name:'班组',exact:true}).selectOption(f.group.id);await expect(page.locator('.daily-table tbody')).toContainText(f.worker.name)
  await page.evaluate(()=>{window.print=()=>window.dispatchEvent(new Event('afterprint'))});const generated=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/daily/prints'&&r.request().method()==='POST');await page.getByRole('button',{name:'打印核对表',exact:true}).click();const printed=await(await generated).json();expect(printed.workers.some((w:any)=>w.workerId===f.worker.id)).toBeTruthy();await expect(page.locator('.list-row').filter({hasText:printed.id}).getByRole('button',{name:'查看旧版与当前差异',exact:true})).toBeVisible()
  await go(page,'采摘账本',width);await page.getByLabel('搜索采摘记录').fill(f.worker.name);row=page.locator('.ledger-table tbody tr');await row.getByRole('button',{name:'编辑数量',exact:true}).click();dialog=page.getByRole('dialog');await dialog.getByLabel('本次地块',{exact:true}).selectOption(f.a.id);await dialog.getByLabel('采摘重量',{exact:true}).fill('25');await dialog.getByLabel('修改原因',{exact:true}).fill('现场复核少记5公斤');await dialog.getByRole('button',{name:'保存记录',exact:true}).click();await expect(dialog).toBeHidden();await expect(row).toContainText('55 公斤')
  await go(page,'每日核对',width);await page.locator('.list-row').filter({hasText:printed.id}).getByRole('button',{name:'查看旧版与当前差异',exact:true}).click();dialog=page.getByRole('dialog',{name:'历史打印快照'});await expect(dialog).toContainText('20公斤');await expect(dialog).toContainText('25公斤');await expect(dialog).toContainText('现场复核少记5公斤');await page.screenshot({path:output+`/snapshot-${width}.png`,fullPage:true});await dialog.getByRole('button',{name:'关闭',exact:true}).click()
  await go(page,'资料批量导入',width);const template=await request.get('/api/imports/workers/template',{headers:f.headers});await page.getByLabel('选择 Excel 文件').setInputFiles({name:'工人资料.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:await template.body()});await page.getByRole('button',{name:'预览并校验',exact:true}).click();await expect(page.locator('tbody')).toContainText('跳过');page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'确认整批导入',exact:true}).click();await expect(page.getByText('整批导入成功',{exact:true})).toBeVisible();expect(errors).toEqual([])
})
