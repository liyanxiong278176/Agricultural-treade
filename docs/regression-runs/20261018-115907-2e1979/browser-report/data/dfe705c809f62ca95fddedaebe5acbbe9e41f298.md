# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: feature-expansion.spec.ts >> 地块、导入和打印快照完整流程 390px
- Location: e2e\feature-expansion.spec.ts:17:31

# Error details

```
Error: expect(locator).toContainText(expected) failed

Locator: locator('.ledger-table tbody tr')
Expected substring: "20 公斤"
Error: strict mode violation: locator('.ledger-table tbody tr') resolved to 20 elements:
    1) <tr class="">…</tr> aka getByRole('row', { name: '扩展工人-e05f1ca E2E-EXT-e05f1ca' })
    2) <tr class="">…</tr> aka getByRole('row', { name: '付款演示工人-09d870 CHANGE-09d870' })
    3) <tr class="">…</tr> aka getByRole('row', { name: '付款演示工人-cf887e CHANGE-cf887e' })
    4) <tr class="">…</tr> aka getByRole('row', { name: '付款演示工人-f6a6de CHANGE-f6a6de' })
    5) <tr class="">…</tr> aka getByRole('row', { name: '验收工人 QA-99869324 2026-10-18 QA 验收 上午 番茄 未分组 未分组 · 未指定地块 5 筐 ¥ 15.00 农场老板 版本 1' })
    6) <tr class="">…</tr> aka getByRole('row', { name: '验收工人 QA-f8174941 2026-10-18' })
    7) <tr class="">…</tr> aka getByRole('row', { name: '验收工人 QA-b54140e0 2026-10-18 QA 验收 上午 番茄 未分组 未分组 · 未指定地块 5 筐 ¥ 15.00 农场老板 版本 1' })
    8) <tr class="">…</tr> aka getByRole('row', { name: '验收工人 QA-4f3346f0 2026-10-18' })
    9) <tr class="">…</tr> aka getByRole('row', { name: '王海生 W003 2026-10-18' })
    10) <tr class="">…</tr> aka getByRole('row', { name: '刘志强 W005 2026-10-18' })
    ...

Call log:
  - Expect "toContainText" locator('.ledger-table tbody tr') with timeout 5000ms
  - waiting for locator('.ledger-table tbody tr')

```

# Page snapshot

```yaml
- generic [ref=e2]:
  - generic [ref=e3]:
    - complementary [ref=e4]:
      - link "田间账本 HARVEST LEDGER" [ref=e5] [cursor=pointer]:
        - /url: "#"
        - generic [ref=e6]:
          - text: 田间账本
          - generic [ref=e7]: HARVEST LEDGER
      - paragraph [ref=e8]: 农场工作台
      - navigation [ref=e9]:
        - button "农场概览" [ref=e10] [cursor=pointer]
        - button "采摘账本" [ref=e17] [cursor=pointer]
        - button "同步中心" [ref=e22] [cursor=pointer]
        - button "每日核对" [ref=e27] [cursor=pointer]
        - button "地块人工成本" [ref=e32] [cursor=pointer]
        - button "资料批量导入" [ref=e39] [cursor=pointer]
        - button "重量配置" [ref=e51] [cursor=pointer]
      - generic [ref=e63]:
        - generic [ref=e69]:
          - strong [ref=e70]: 单基地 · 农场演示
          - generic [ref=e71]: 让每一笔劳动有据可查
        - button "退出登录" [ref=e72] [cursor=pointer]
    - generic [ref=e76]:
      - banner [ref=e77]:
        - generic [ref=e78]:
          - button "打开导航" [ref=e79] [cursor=pointer]
          - strong [ref=e81]: 采摘账本
        - generic [ref=e82]:
          - generic [ref=e83]: 网络正常
          - button "退出登录" [ref=e85] [cursor=pointer]
      - main [ref=e90]:
        - generic [ref=e91]:
          - generic [ref=e92]: 农场采摘 · 工作账本
          - generic [ref=e97]:
            - textbox [ref=e99]: 2026-10
            - button "刷新数据" [ref=e100] [cursor=pointer]
        - generic [ref=e106]:
          - generic [ref=e107]:
            - paragraph [ref=e108]: 按日、按时段，记清每笔工作量
            - heading "采摘账本" [level=1] [ref=e109]
            - paragraph [ref=e110]: 同一工人、日期、时段和产品只保留一条记录。
          - generic [ref=e111]:
            - button "连续录入" [ref=e112] [cursor=pointer]
            - button "记一笔采摘" [ref=e113] [cursor=pointer]
        - generic [ref=e115]:
          - textbox "搜索采摘记录" [active] [ref=e120]:
            - /placeholder: 查找工人、工号或产品
            - text: 扩展工人-5525963
          - generic [ref=e121]:
            - button "全部时段" [ref=e122] [cursor=pointer]
            - button "上午" [ref=e123] [cursor=pointer]
            - button "下午" [ref=e130] [cursor=pointer]
          - generic [ref=e136]:
            - text: 班组
            - combobox "班组" [ref=e137]:
              - option "全部班组" [selected]
              - option "未分组"
              - option "其他组-09d870"
              - option "原组-09d870"
              - option "扩展组-5525963"
              - option "扩展组-e05f1ca"
              - option "新组-09d870"
          - generic [ref=e138]:
            - text: 开始日期
            - textbox "采摘开始日期" [ref=e139]
          - generic [ref=e140]:
            - text: 结束日期
            - textbox "采摘结束日期" [ref=e141]
          - generic [ref=e142]:
            - text: 产品
            - combobox "查询产品" [ref=e143]:
              - option "全部产品" [selected]
              - option "小白菜"
              - option "新流程番茄-09d870"
              - option "新流程番茄-a69e79"
              - option "新流程番茄-cf887e"
              - option "新流程番茄-f6a6de"
              - option "番茄"
              - option "豇豆"
              - option "验收称重a2b3c8"
              - option "黄瓜"
          - button "查询" [ref=e144] [cursor=pointer]
          - button "重置" [ref=e145] [cursor=pointer]
          - generic [ref=e146]:
            - checkbox "含已删除" [ref=e147]
            - text: 含已删除
        - region "采摘账本" [ref=e148]:
          - table [ref=e150]:
            - rowgroup [ref=e151]:
              - row [ref=e152]:
                - columnheader "工人 / 工号" [ref=e153]
                - columnheader "采摘日期" [ref=e154]
                - columnheader "时段" [ref=e155]
                - columnheader "产品" [ref=e156]
                - columnheader "工作量" [ref=e157]
                - columnheader "核算工资" [ref=e158]
                - columnheader "最近修改" [ref=e159]
                - columnheader "状态" [ref=e160]
                - columnheader "操作" [ref=e161]
            - rowgroup [ref=e162]:
              - row [ref=e163]:
                - cell "扩展工人-5525963 E2E-EXT-5525963" [ref=e164]:
                  - strong [ref=e165]: 扩展工人-5525963
                  - generic [ref=e166]: E2E-EXT-5525963
                - cell "2026-10-18" [ref=e167]
                - cell "上午" [ref=e168]
                - cell "小白菜 扩展组-5525963 扩展组-5525963 · 一号棚-5525963" [ref=e176]:
                  - text: 小白菜
                  - generic [ref=e177]: 扩展组-5525963
                  - generic [ref=e178]: 扩展组-5525963 · 一号棚-5525963
                - cell "20 公斤" [ref=e179]
                - cell "¥ 10.00" [ref=e181]
                - cell "记录员小林 版本 1" [ref=e182]:
                  - text: 记录员小林
                  - generic [ref=e183]: 版本 1
                - cell "未结算" [ref=e184]
                - cell [ref=e186]:
                  - generic [ref=e187]:
                    - button "增加采摘量" [ref=e188] [cursor=pointer]
                    - button "更正地块" [ref=e189] [cursor=pointer]
                    - button "修改记录" [ref=e190] [cursor=pointer]
                    - button "编辑数量" [ref=e191] [cursor=pointer]:
                      - generic [ref=e194]: 更正数量
                    - button "删除记录" [ref=e195] [cursor=pointer]:
                      - generic [ref=e199]: 删除
          - generic "采摘账本分页" [ref=e200]:
            - generic [ref=e201]: 共 1 条 · 第 1 / 1 页
            - generic [ref=e202]:
              - text: 每页
              - combobox "每页条数" [ref=e203]:
                - option "10条"
                - option "20条" [selected]
                - option "50条"
            - button "上一页" [disabled] [ref=e204]
            - button "下一页" [disabled] [ref=e205]
        - generic [ref=e206]:
          - generic [ref=e207]: 田间账本 · 农场采摘计件计重管理
          - generic [ref=e208]: 离线页面已准备
    - navigation [ref=e212]:
      - button "农场概览" [ref=e213] [cursor=pointer]
      - button "采摘账本" [ref=e220] [cursor=pointer]
      - button "同步中心" [ref=e225] [cursor=pointer]
      - button "每日核对" [ref=e230] [cursor=pointer]
  - status: 保存成功
```

# Test source

```ts
  1  | import {test,expect,type Page,type APIRequestContext} from '@playwright/test'
  2  | import {randomUUID} from 'node:crypto'
  3  | import {mkdirSync} from 'node:fs'
  4  | import {evidenceDir} from './artifacts'
  5  | const output=evidenceDir
  6  | mkdirSync(output,{recursive:true})
  7  | async function fixture(request:APIRequestContext){
  8  |   const auth=await(await request.post('/api/login',{data:{username:'admin',password:'Farm@2026'}})).json()
  9  |   const headers={Authorization:'Bearer '+auth.token},suffix=randomUUID().slice(0,7)
  10 |   async function save(kind:string,data:object){const r=await request.post('/api/catalog/'+kind,{headers,data});expect(r.ok(),await r.text()).toBeTruthy();return r.json()}
  11 |   const group=await save('groups',{name:'扩展组-'+suffix}),a=await save('plots',{name:'一号棚-'+suffix}),b=await save('plots',{name:'二号棚-'+suffix}),worker=await save('workers',{name:'扩展工人-'+suffix,code:'E2E-EXT-'+suffix,groupId:group.id})
  12 |   const boot=await(await request.get('/api/bootstrap',{headers})).json()
  13 |   return {headers,group,a,b,worker,product:boot.products.find((p:any)=>p.name==='小白菜'),date:boot.today}
  14 | }
  15 | async function login(page:Page){await page.goto('/');await page.getByLabel('账号',{exact:true}).fill('recorder');await page.getByLabel('密码',{exact:true}).fill('Farm@2026');await page.getByRole('button',{name:'登录账本'}).click();await expect(page.locator('.app-layout')).toBeVisible()}
  16 | async function go(page:Page,name:string,width:number){if(width<800)await page.getByRole('button',{name:'打开导航',exact:true}).click();await page.locator('.sidebar').getByRole('button',{name,exact:true}).click()}
  17 | for(const width of [1440,390])test(`地块、导入和打印快照完整流程 ${width}px`,async({page,request,context})=>{
  18 |   test.setTimeout(120000);page.setDefaultTimeout(12000);await page.setViewportSize({width,height:1000})
  19 |   const f=await fixture(request),errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));await login(page)
  20 |   await expect(page.locator('.sidebar').getByRole('button',{name:'每日派工',exact:true})).toHaveCount(0)
  21 |   await go(page,'采摘账本',width);await page.getByRole('button',{name:'记一笔采摘',exact:true}).first().click();let dialog=page.getByRole('dialog')
  22 |   await dialog.getByLabel('工人',{exact:true}).selectOption(f.worker.id);await dialog.getByLabel('产品',{exact:true}).selectOption(f.product.id);await dialog.getByRole('button',{name:'上午',exact:true}).click();await dialog.getByLabel('本次所属班组',{exact:true}).selectOption(f.group.id);await dialog.getByLabel('本次地块',{exact:true}).selectOption(f.a.id);await dialog.getByLabel('采摘重量',{exact:true}).fill('20');await dialog.getByRole('button',{name:'保存记录',exact:true}).click();await expect(dialog).toBeHidden()
> 23 |   await page.getByLabel('搜索采摘记录').fill(f.worker.name);let row=page.locator('.ledger-table tbody tr');await expect(row).toContainText('20 公斤')
     |                                                                                                                        ^ Error: expect(locator).toContainText(expected) failed
  24 |   await row.getByRole('button',{name:'增加采摘量',exact:true}).click();dialog=page.getByRole('dialog');await dialog.getByLabel('本次地块',{exact:true}).selectOption(f.b.id);await dialog.getByLabel('采摘重量',{exact:true}).fill('30');await dialog.getByRole('button',{name:'保存记录',exact:true}).click();await expect(dialog).toBeHidden();await expect(row).toContainText('50 公斤');await expect(row).toHaveCount(1)
  25 |   await go(page,'地块人工成本',width);await expect(page.locator('tbody')).toContainText(f.a.name);await expect(page.locator('tbody')).toContainText(f.b.name);await page.screenshot({path:output+`/costs-${width}.png`,fullPage:true})
  26 |   await go(page,'每日核对',width);await page.getByRole('combobox',{name:'班组',exact:true}).selectOption(f.group.id);await expect(page.locator('.daily-table tbody')).toContainText(f.worker.name)
  27 |   await page.evaluate(()=>{window.print=()=>window.dispatchEvent(new Event('afterprint'))});await page.getByRole('button',{name:'打印核对表',exact:true}).click();await expect(page.getByRole('button',{name:'查看旧版与当前差异',exact:true}).first()).toBeVisible()
  28 |   await go(page,'采摘账本',width);await page.getByLabel('搜索采摘记录').fill(f.worker.name);row=page.locator('.ledger-table tbody tr');await row.getByRole('button',{name:'编辑数量',exact:true}).click();dialog=page.getByRole('dialog');await dialog.getByLabel('本次地块',{exact:true}).selectOption(f.a.id);await dialog.getByLabel('采摘重量',{exact:true}).fill('25');await dialog.getByLabel('修改原因',{exact:true}).fill('现场复核少记5公斤');await dialog.getByRole('button',{name:'保存记录',exact:true}).click();await expect(dialog).toBeHidden();await expect(row).toContainText('55 公斤')
  29 |   await go(page,'每日核对',width);await page.getByRole('button',{name:'查看旧版与当前差异',exact:true}).first().click();dialog=page.getByRole('dialog',{name:'历史打印快照'});await expect(dialog).toContainText('20公斤');await expect(dialog).toContainText('25公斤');await expect(dialog).toContainText('现场复核少记5公斤');await page.screenshot({path:output+`/snapshot-${width}.png`,fullPage:true});await dialog.getByRole('button',{name:'关闭',exact:true}).click()
  30 |   await go(page,'资料批量导入',width);const template=await request.get('/api/imports/workers/template',{headers:f.headers});await page.getByLabel('选择 Excel 文件').setInputFiles({name:'工人资料.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:await template.body()});await page.getByRole('button',{name:'预览并校验',exact:true}).click();await expect(page.locator('tbody')).toContainText('跳过');page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'确认整批导入',exact:true}).click();await expect(page.getByText('整批导入成功',{exact:true})).toBeVisible();expect(errors).toEqual([])
  31 | })
  32 | 
```