import {test,expect} from '@playwright/test'
import {mkdirSync} from 'node:fs'
import {artifact} from './artifacts'

for(const width of [1440,390])for(const outcome of ['accepted','rejected','unreachable','conflict','offline']){
  test(`${width}px ${outcome} 在线直提与结算删除权限`,async({page})=>{
    const date=new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Singapore'})
    const yesterday=new Date(date+'T12:00:00');yesterday.setDate(yesterday.getDate()-1)
    const prior=yesterday.toLocaleDateString('sv-SE',{timeZone:'Asia/Singapore'})
    const actor={id:'recorder-test',name:'记录员测试',username:'recorder',role:'RECORDER'}
    const rule={mode:'SEGMENTS',stages:[{key:'main',label:'采摘筐数',type:'PIECE',rate:'3'}]}
    const rows=[
      {id:'own',worker_name:'本人记录',created_by:actor.id,work_date:date},
      {id:'other',worker_name:'他人记录',created_by:'another-recorder',work_date:date},
      {id:'past',worker_name:'往日记录',created_by:actor.id,work_date:prior},
      {id:'settled',worker_name:'已结算记录',created_by:actor.id,work_date:date,settled:true},
    ].map((r,index)=>({worker_id:'worker-'+index,worker_code:'W'+index,product_id:'tomato',product_name:'番茄',period:'AM',inputs_json:JSON.stringify({quantities:{main:'5'},totalWeight:null,weightUnit:'kg'}),note:'',updated_by:actor.id,updated_name:actor.name,creator_name:actor.name,version:1,deleted:false,settled:false,quote:{amount:15,parts:[{label:'采摘筐数',type:'PIECE',quantity:5,rate:3,amount:15}],remainderKg:0},...r}))
    const sent:string[]=[]
    // UI fixture: no real database writes or already-settled demo records are touched.
    await page.route('**/api/**',async route=>{
      const path=new URL(route.request().url()).pathname
      let body:unknown
      if(path==='/api/login')body={token:'test-token',user:actor}
      else if(path==='/api/bootstrap')body={user:actor,today:date,workers:rows.map(r=>({id:r.worker_id,name:r.worker_name,code:r.worker_code,phone:'',active:true})),categories:[{id:'fruit',name:'瓜果类',active:true}],products:[{id:'tomato',name:'番茄',category_id:'fruit',active:true}],rules:[{id:'rule',product_id:'tomato',effective_date:'2000-01-01',end_date:null,revision:1,created_at:'2000-01-01T00:00:00',rule_json:JSON.stringify(rule),change_reason:'界面测试规则'}]}
      else if(path==='/api/records')body=rows
      else if(path==='/api/query/records'){
        const params=new URL(route.request().url()).searchParams,q=params.get('q')||'',size=Number(params.get('size')||20),pageNumber=Number(params.get('page')||1)
        const filtered=rows.filter(r=>(params.get('deleted')==='true'||!r.deleted)&&(!q||(r.worker_name+r.worker_code+r.product_name).includes(q)))
        body={items:filtered.slice((pageNumber-1)*size,pageNumber*size),total:filtered.length,page:pageNumber,size}
      }
      else if(path==='/api/records/operate'){
        const op=route.request().postDataJSON()
        sent.push(op.action)
        if(outcome==='unreachable'){await route.abort('connectionfailed');return}
        if(outcome==='rejected'){await route.fulfill({status:409,json:{message:'该记录已纳入确认结算，不能直接删除'}});return}
        const record=rows.find(r=>r.id===op.recordId)!
        if(outcome==='conflict'&&op.version===1){record.version=2;record.inputs_json=JSON.stringify({...op.inputs,quantities:{main:'8'}});record.quote.parts[0].quantity=8;body={status:'VERSION_CONFLICT',message:'记录已被更新',record};await route.fulfill({json:body});return}
        record.inputs_json=JSON.stringify(op.inputs);record.quote.parts[0].quantity=Number(op.inputs.quantities.main)
        record.deleted=op.action==='DELETE';record.version++
        body={status:'ACCEPTED',message:'后台已接收',record}
      }
      else if(path==='/api/sync'){sent.push('SYNC');body=route.request().postDataJSON().map((op:any)=>{const record=rows.find(r=>r.id===op.recordId)!;record.deleted=op.action==='DELETE';record.version++;return {operationId:op.operationId,result:{status:'ACCEPTED',message:'已接收',record}}})}
      else body={status:'ok'}
      await route.fulfill({json:body})
    })
    await page.setViewportSize({width,height:900})
    await page.goto('/')
    await page.getByLabel('账号',{exact:true}).fill('recorder')
    await page.getByLabel('密码',{exact:true}).fill('Farm@2026')
    await page.getByRole('button',{name:'登录账本'}).click()
    await expect(page.locator('.stat').nth(3).locator('strong')).toHaveText('1种')
    await page.locator(width<800?'.bottom-nav':'.sidebar').getByRole('button',{name:'采摘账本',exact:true}).click()
    const own=page.getByRole('row').filter({hasText:'本人记录'})
    const remove=own.getByRole('button',{name:'删除记录',exact:true})
    await expect(remove).toHaveText('删除')
    await expect(remove).toBeEnabled()
    await expect(page.getByRole('row').filter({hasText:'他人记录'}).getByRole('button',{name:'删除记录',exact:true})).toBeDisabled()
    await expect(page.getByRole('row').filter({hasText:'往日记录'}).getByRole('button',{name:'删除记录',exact:true})).toBeEnabled()
    await expect(page.getByRole('row').filter({hasText:'已结算记录'}).getByRole('button',{name:'删除记录',exact:true})).toBeDisabled()
    if(outcome==='conflict'){
      await own.getByRole('button',{name:'编辑数量'}).click()
      const editor=page.getByRole('dialog',{name:'编辑采摘记录'})
      await editor.getByLabel('采摘筐数',{exact:true}).fill('7')
      await editor.getByLabel('修改原因',{exact:true}).fill('核对后更正数量')
      await editor.getByRole('button',{name:'保存记录'}).click()
      await expect(editor.getByLabel('采摘筐数',{exact:true})).toHaveValue('7')
      await expect(editor).toContainText('当前最新数量为 8筐')
      await editor.getByRole('button',{name:'保留我的输入，核对后重提'}).click()
      await editor.getByRole('button',{name:'保存记录'}).click()
      await expect(editor).toBeHidden()
      await expect(own).toContainText('7 筐')
      await expect(page.locator('.toast')).toHaveText('保存成功')
      expect(sent).toEqual(['UPDATE','UPDATE'])
      await page.locator(width<800?'.bottom-nav':'.sidebar').getByRole('button',{name:/同步中心/}).click()
      await expect(page.getByRole('heading',{name:'本机操作已处理'})).toBeVisible()
      return
    }
    if(outcome==='offline'){
      await page.context().setOffline(true)
      page.once('dialog',dialog=>dialog.accept())
      await remove.click()
      await expect(page.locator('.toast')).toContainText('已保存在本机')
      await expect(remove).toBeDisabled()
      expect(sent).toEqual([])
      await page.context().setOffline(false)
      await expect(own).toHaveCount(0)
      expect(sent).toEqual(['SYNC'])
      await page.locator(width<800?'.bottom-nav':'.sidebar').getByRole('button',{name:/同步中心/}).click()
      await expect(page.getByRole('heading',{name:'本机操作已处理'})).toBeVisible()
      return
    }
    const box=await remove.boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x+box!.width).toBeLessThanOrEqual(width)
    page.once('dialog',dialog=>dialog.dismiss())
    await remove.click()
    expect(sent).toEqual([])
    await expect(own).toBeVisible()
    page.once('dialog',dialog=>dialog.accept())
    await remove.click()
    if(outcome!=='accepted'){
      await expect(own).toBeVisible()
      if(outcome==='rejected'){
        await expect(page.getByRole('alert')).toContainText('已纳入确认结算')
        await expect(remove).toBeEnabled()
      }else{
        await expect(page.locator('.toast')).toContainText('已保存在本机')
        await expect(remove).toBeDisabled()
      }
      await page.locator(width<800?'.bottom-nav':'.sidebar').getByRole('button',{name:/同步中心/}).click()
      if(outcome==='rejected')await expect(page.getByRole('heading',{name:'本机操作已处理'})).toBeVisible()
      else await expect(page.locator('.sync-item')).toHaveCount(1)
      expect(sent).toEqual(['DELETE'])
      return
    }
    await expect(own).toHaveCount(0)
    expect(sent).toEqual(['DELETE'])
    await expect(page.locator('.toast')).toHaveText('删除成功')
    await expect(page.locator('.toast')).toBeHidden({timeout:4500})
    await page.getByLabel('含已删除').check()
    await expect(own).toContainText('已删除')
    page.once('dialog',dialog=>dialog.accept())
    await own.getByRole('button',{name:'恢复',exact:true}).click()
    await expect(own).toContainText('未结算')
    await expect(own.getByRole('button',{name:'删除记录',exact:true})).toBeEnabled()
    expect(sent).toEqual(['DELETE','RESTORE'])
    const past=page.getByRole('row').filter({hasText:'往日记录'})
    page.once('dialog',dialog=>dialog.accept())
    await past.getByRole('button',{name:'删除记录',exact:true}).click()
    await expect(past).toContainText('已删除')
    expect(sent).toEqual(['DELETE','RESTORE','DELETE'])
    await page.screenshot({path:artifact(`ledger-delete-${width}-${outcome}.png`)})
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
  })
}
