import {test,expect} from '@playwright/test'
import {randomUUID} from 'node:crypto'
import {appendFileSync,mkdirSync} from 'node:fs'
import {artifact} from './artifacts'

for(const width of [1440,390]){
  test(`记录员在 ${width}px 采摘账本新增及操作反馈，顶部提示自动消失`,async({page,request})=>{
    const auth=await request.post('/api/login',{data:{username:'admin',password:'Farm@2026'}})
    expect(auth.ok()).toBeTruthy()
    const headers={Authorization:'Bearer '+(await auth.json()).token}
    const created=await request.post('/api/catalog/workers',{headers,data:{name:'提示验收'+randomUUID().slice(0,6),code:'E2E-TOAST-'+randomUUID().slice(0,8),phone:'',active:true}})
    expect(created.ok()).toBeTruthy()
    const worker=await created.json()
    appendFileSync(artifact('toast-test-workers.txt'),worker.id+'\n')
    const bootstrap=await(await request.get('/api/bootstrap',{headers})).json()
    const product=bootstrap.products.find((p:any)=>p.name==='番茄')

    await page.setViewportSize({width,height:900})
    await page.goto('/')
    await page.getByLabel('账号',{exact:true}).fill('recorder')
    await page.getByLabel('密码',{exact:true}).fill('Farm@2026')
    await page.getByRole('button',{name:'登录账本'}).click()
    await expect(page.locator('.stat').nth(3).locator('strong')).toHaveText(/[1-9]/)
    await page.locator(width<800?'.bottom-nav':'.sidebar').getByRole('button',{name:'采摘账本',exact:true}).click()
    await page.getByLabel('搜索采摘记录').fill(worker.name)
    const entry=page.getByRole('button',{name:'记一笔采摘',exact:true}).first()
    await expect(entry).toBeVisible()
    await entry.click()
    await page.getByLabel('工人',{exact:true}).selectOption(worker.id)
    await page.getByLabel('产品',{exact:true}).selectOption(product.id)
    await page.getByLabel('采摘筐数',{exact:true}).fill('5.5')
    await page.getByRole('button',{name:'保存记录',exact:true}).click()
    const toast=page.locator('.toast')
    await expect(toast).toHaveText('保存成功')
    let position=await toast.boundingBox()
    expect(position!.y).toBeGreaterThanOrEqual(0)
    expect(position!.y).toBeLessThan(80)
    await page.screenshot({path:artifact(`recorder-toast-top-${width}.png`)})
    await expect(toast).toBeHidden({timeout:4500})

    await page.getByLabel('含已删除').check()
    await expect(page.locator('tbody tr')).toHaveCount(1)
    page.once('dialog',dialog=>dialog.accept())
    await page.getByRole('button',{name:'删除记录',exact:true}).click()
    await expect(toast).toHaveText('删除成功')
    position=await toast.boundingBox()
    expect(position!.y).toBeLessThan(80)
    await expect(toast).toBeHidden({timeout:4500})
    // Online operations are acknowledged by the real backend immediately.
    // Offline feedback is covered separately in ledger/deletion tests.
  })
}
