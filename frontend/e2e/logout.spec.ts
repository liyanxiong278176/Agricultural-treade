import {test,expect} from '@playwright/test'

for(const viewport of [{width:1440,height:1000},{width:390,height:844}]){
  test(`顶部退出登录在 ${viewport.width}px 可用，并注销后台会话`,async({page,request})=>{
    await page.setViewportSize(viewport)
    await page.goto('/')
    await page.getByLabel('账号',{exact:true}).fill('recorder')
    await page.getByLabel('密码',{exact:true}).fill('Farm@2026')
    await page.getByRole('button',{name:'登录账本'}).click()
    const exit=page.locator('.topbar').getByRole('button',{name:'退出登录',exact:true})
    await expect(exit).toBeVisible()
    const token=await page.evaluate(()=>JSON.parse(localStorage.getItem('farm-session')!).token)
    const revoked=page.waitForResponse(r=>r.url().endsWith('/api/logout')&&r.request().method()==='POST')
    await exit.click()
    await expect(page.getByRole('button',{name:'登录账本'})).toBeVisible()
    expect((await revoked).ok()).toBeTruthy()
    expect((await request.get('/api/bootstrap',{headers:{Authorization:'Bearer '+token}})).status()).toBe(401)
    await page.reload()
    await expect(page.getByRole('button',{name:'登录账本'})).toBeVisible()
    expect(await page.evaluate(()=>localStorage.getItem('farm-session'))).toBeNull()
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
  })
}
