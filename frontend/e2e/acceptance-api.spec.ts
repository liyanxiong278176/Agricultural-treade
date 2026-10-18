import {test,expect,type APIRequestContext} from '@playwright/test'
import {randomUUID} from 'node:crypto'
import {writeFileSync} from 'node:fs'
import {artifact} from './artifacts'

const date=()=>new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Singapore'})
const month=()=>date().slice(0,7)
const headers=(token:string)=>({Authorization:'Bearer '+token})
async function login(request:APIRequestContext,username='admin'){
  const r=await request.post('/api/login',{data:{username,password:'Farm@2026'}})
  expect(r.status()).toBe(200);return (await r.json()).token as string
}
async function save(request:APIRequestContext,token:string,kind:string,data:unknown){
  const r=await request.post('/api/catalog/'+kind,{headers:headers(token),data})
  expect(r.status(),await r.text()).toBe(200);return r.json()
}
async function fixture(request:APIRequestContext){
  const token=await login(request)
  const worker=await save(request,token,'workers',{name:'验收工人',code:'QA-'+randomUUID().slice(0,8),phone:'13800000000',active:true})
  const boot=await(await request.get('/api/bootstrap',{headers:headers(token)})).json()
  return {token,worker,boot,product:boot.products.find((p:any)=>p.name==='番茄')}
}
function operation(workerId:string,productId:string,quantity='5'){
  return {operationId:randomUUID(),recordId:randomUUID(),action:'CREATE',workerId,productId,workDate:date(),period:'AM',inputs:{quantities:{main:quantity},totalWeight:null,weightUnit:'kg'},note:'QA 验收',version:null as number|null,reason:''}
}

test('API-01 冒烟、未登录、错误登录、记录员越权和非法月份',async({request})=>{
  expect((await request.get('/api/health')).status()).toBe(200)
  expect((await request.get('/api/bootstrap')).status()).toBe(401)
  expect((await request.post('/api/login',{data:{username:'admin',password:'wrong'}})).status()).toBe(401)
  const token=await login(request,'recorder')
  for(const path of ['/api/users','/api/audit','/api/payroll?month='+month(),'/api/payroll/export?month='+month()])
    expect((await request.get(path,{headers:headers(token)})).status(),path).toBe(403)
  expect((await request.post('/api/catalog/workers',{headers:headers(token),data:{name:'越权'}})).status()).toBe(403)
  expect((await request.get('/api/records?month=bad',{headers:headers(token)})).status()).toBe(400)
})

test('API-02 工人新增、修改、重复工号、停用拦截',async({request})=>{
  const {token,worker,product}=await fixture(request)
  const changed=await save(request,token,'workers',{...worker,name:'修改后工人',phone:'13900000000',active:false})
  expect(changed.name).toBe('修改后工人');expect(changed.phone).toBe('13900000000');expect(changed.active).toBeFalsy()
  expect((await request.post('/api/catalog/workers',{headers:headers(token),data:{name:'重复',code:worker.code}})).status()).toBe(409)
  const recorder=await login(request,'recorder')
  const r=await request.post('/api/records/operate',{headers:headers(recorder),data:operation(worker.id,product.id)})
  expect(r.status()).toBe(400);expect(await r.text()).toContain('停用')
})

test('API-03 账号创建、角色调整、停用撤销已登录权限、自我保护',async({request})=>{
  const token=await login(request), username='qa_'+randomUUID().slice(0,8)
  const user=await save(request,token,'users',{name:'验收记录员',username,password:'Farm@2026',role:'RECORDER',active:true})
  const userToken=await login(request,username)
  expect((await request.get('/api/users',{headers:headers(userToken)})).status()).toBe(403)
  await save(request,token,'users',{id:user.id,name:'验收管理员',username,password:'',role:'ADMIN',active:true})
  expect((await request.get('/api/users',{headers:headers(userToken)})).status()).toBe(200)
  await save(request,token,'users',{id:user.id,name:'已停用',username,password:'',role:'RECORDER',active:false})
  expect((await request.get('/api/bootstrap',{headers:headers(userToken)})).status()).toBe(401)
  expect((await request.post('/api/login',{data:{username,password:'Farm@2026'}})).status()).toBe(401)
  const users=await(await request.get('/api/users',{headers:headers(token)})).json()
  const admin=users.find((u:any)=>u.username==='admin')
  expect((await request.post('/api/catalog/users',{headers:headers(token),data:{...admin,name:'老板',active:false}})).status()).toBe(400)
})

test('API-04 类别产品配置及公斤、斤、克计价和停用校验',async({request})=>{
  const {token,worker}=await fixture(request)
  const category=await save(request,token,'categories',{name:'验收类别'+randomUUID().slice(0,6)})
  const product=await save(request,token,'products',{name:'验收称重'+randomUUID().slice(0,6),categoryId:category.id})
  const rule={mode:'SEGMENTS',stages:[{key:'weight',label:'手工重量',type:'WEIGHT',rate:'0.5'}]}
  const published=await request.post('/api/rules',{headers:headers(token),data:{productId:product.id,effectiveDate:date(),rule,reason:'验收计重',scope:'FROM_DATE'}})
  expect(published.status()).toBe(200)
  for(const [unit,value] of [['kg','10'],['jin','20'],['g','10000']]){
    const trial=await request.post('/api/rules/trial',{headers:headers(token),data:{rule,inputs:{quantities:{weight:value},totalWeight:null,weightUnit:unit}}})
    expect(trial.status()).toBe(200);expect((await trial.json()).amount).toBe(5)
  }
  const op={...operation(worker.id,product.id),inputs:{quantities:{weight:'20'},totalWeight:null,weightUnit:'jin'}}
  const r=await request.post('/api/records/operate',{headers:headers(token),data:op})
  expect(r.status()).toBe(200);expect((await r.json()).record.quote.amount).toBe(5)
  await save(request,token,'categories',{...category,active:false})
  expect((await request.post('/api/records/operate',{headers:headers(token),data:{...op,operationId:randomUUID(),recordId:randomUUID(),period:'PM'}})).status()).toBe(400)
})

test('API-05 数量、未来日期、补录说明、UUID、空批次及超大批次校验',async({request})=>{
  const {token,worker,product}=await fixture(request), op=operation(worker.id,product.id)
  const invalid=[{...op,operationId:'bad'}, {...op,inputs:null}, {...op,inputs:{...op.inputs,quantities:{main:'-1'}}}, {...op,inputs:{...op.inputs,quantities:{main:'0'}}}, {...op,inputs:{...op.inputs,quantities:{main:'1.2'}}}, {...op,workDate:'2099-01-01'}, {...op,workDate:'2026-01-01',note:''}]
  for(const data of invalid)expect((await request.post('/api/records/operate',{headers:headers(token),data})).status(),JSON.stringify(data)).toBe(400)
  const empty=await request.post('/api/sync',{headers:headers(token),data:[]});expect(empty.status()).toBe(200);expect(await empty.json()).toEqual([])
  for(const data of [[null],Array(101).fill(op)])expect((await request.post('/api/sync',{headers:headers(token),data})).status()).toBe(400)
})

test('API-06 同UUID重试、内容绑定、跨账号防复用和混合批次隔离',async({request})=>{
  const {token,worker,product}=await fixture(request), op=operation(worker.id,product.id)
  for(let n=0;n<3;n++){
    const r=await request.post(n===0?'/api/records/operate':'/api/sync',{headers:headers(token),data:n===0?op:[op]})
    expect(r.status()).toBe(200);const body=await r.json();expect(n===0?body.status:body[0].result.status).toBe('ACCEPTED')
  }
  expect((await request.post('/api/records/operate',{headers:headers(token),data:{...op,note:'复用但不同内容'}})).status()).toBe(409)
  const other=await login(request,'recorder2')
  expect((await request.post('/api/records/operate',{headers:headers(other),data:op})).status()).toBe(409)
  const response=await request.post('/api/sync',{headers:headers(token),data:[{...operation(worker.id,product.id),operationId:'invalid'}, {...operation(worker.id,product.id),period:'PM'}]})
  expect((await response.json()).map((r:any)=>r.result.status)).toEqual(['REJECTED','ACCEPTED'])
  const rows=await(await request.get('/api/records?month='+month(),{headers:headers(token)})).json()
  expect(rows.filter((r:any)=>r.worker_id===worker.id)).toHaveLength(2)
  const logs=await(await request.get('/api/audit',{headers:headers(token)})).json()
  expect(logs.filter((l:any)=>l.entity_id===op.recordId&&l.action==='CREATE')).toHaveLength(1)
})

test('API-07 修改、逻辑删除、恢复与完整前后值审计',async({request})=>{
  const {token,worker,product}=await fixture(request), recorder=await login(request,'recorder'), op=operation(worker.id,product.id)
  const send=async(data:unknown)=>request.post('/api/records/operate',{headers:headers(recorder),data})
  expect((await send(op)).status()).toBe(200)
  expect((await send({...op,action:'UPDATE',operationId:randomUUID(),version:1,inputs:{...op.inputs,quantities:{main:'6.5'}}})).status()).toBe(200)
  expect((await send({...op,action:'DELETE',operationId:randomUUID(),version:2,reason:'验收误删'})).status()).toBe(200)
  const rows=await(await request.get('/api/records?month='+month(),{headers:headers(token)})).json()
  expect(rows.find((r:any)=>r.id===op.recordId).deleted).toBeTruthy()
  expect((await send({...op,action:'RESTORE',operationId:randomUUID(),version:3,reason:'验收恢复'})).status()).toBe(200)
  const logs=await(await request.get('/api/audit',{headers:headers(token)})).json()
  const own=logs.filter((l:any)=>l.entity_id===op.recordId)
  expect(own.map((l:any)=>l.action).sort()).toEqual(['CREATE','DELETE','RESTORE','UPDATE'])
  const edit=own.find((l:any)=>l.action==='UPDATE')
  expect(JSON.parse(JSON.parse(edit.before_json).inputs_json).quantities.main).toBe(5)
  expect(JSON.parse(JSON.parse(edit.after_json).inputs_json).quantities.main).toBe(6.5)
  expect(edit.actor_name).toBeTruthy();expect(edit.created_at).toBeTruthy()
})

test('API-08 并发重放及相同业务键只能入账一次',async({request})=>{
  const {token,worker,product}=await fixture(request), op=operation(worker.id,product.id)
  const repeated=await Promise.all(Array.from({length:6},()=>request.post('/api/records/operate',{headers:headers(token),data:op})))
  for(const r of repeated){expect(r.status()).toBe(200);expect((await r.json()).status).toBe('ACCEPTED')}
  const races=await Promise.all(Array.from({length:4},()=>request.post('/api/records/operate',{headers:headers(token),data:{...operation(worker.id,product.id),period:'PM'}})))
  const statuses=await Promise.all(races.map(async r=>{expect(r.status()).toBe(200);return (await r.json()).status}))
  expect(statuses.filter(s=>s==='ACCEPTED')).toHaveLength(1)
  expect(statuses.filter(s=>s==='DUPLICATE_CONFLICT')).toHaveLength(3)
})

test('API-09 月度汇总、结算、晚传补结算、分次付款和真实Excel文件',async({request})=>{
  const {token,worker,product}=await fixture(request)
  // A separate historical month keeps this settlement out of current-day browser flows.
  const workDate='2025-08-15', m='2025-08'
  const send=async(period:string,quantity:string)=>request.post('/api/records/operate',{headers:headers(token),data:{...operation(worker.id,product.id,quantity),workDate,period,note:'验收历史补录'}})
  // Demo rules begin in 2000; explicitly publish if that ever changes.
  const bootstrap=await(await request.get('/api/bootstrap',{headers:headers(token)})).json()
  const rule=JSON.parse(bootstrap.rules.find((r:any)=>r.product_id===product.id).rule_json)
  expect((await request.post('/api/rules',{headers:headers(token),data:{productId:product.id,effectiveDate:'2025-08-01',rule,reason:'历史验收价',scope:'MONTH'}})).status()).toBe(200)
  expect((await send('AM','5')).status()).toBe(200)
  const preview=async()=> (await(await request.get('/api/payroll?month='+m,{headers:headers(token)})).json()).workers.find((w:any)=>w.workerId===worker.id)
  expect((await preview()).earned).toBe(15);expect((await preview()).pieces).toBe(5)
  expect((await request.post('/api/payroll/'+m+'/confirm',{headers:headers(token)})).status()).toBe(200)
  expect((await send('PM','2')).status()).toBe(200)
  let p=await preview();expect(p.earned).toBe(21);expect(p.settled).toBe(15);expect(p.pending).toBe(6)
  expect((await request.post('/api/payroll/'+m+'/confirm',{headers:headers(token)})).status()).toBe(200)
  const payment={id:randomUUID(),workerId:worker.id,month:m,amount:'10.00',method:'现金',note:'验收部分付款'}
  for(let n=0;n<2;n++)expect((await request.post('/api/payments',{headers:headers(token),data:payment})).status()).toBe(200)
  p=await preview();expect(p.paid).toBe(10);expect(p.unpaid).toBe(11)
  expect((await request.post('/api/payments',{headers:headers(token),data:{...payment,id:randomUUID(),amount:'12'}})).status()).toBe(400)
  const exported=await request.get('/api/payroll/export?month='+m,{headers:headers(token)})
  expect(exported.status()).toBe(200);expect(exported.headers()['content-type']).toContain('spreadsheetml')
  const bytes=await exported.body();expect(bytes.subarray(0,2).toString()).toBe('PK')
  writeFileSync(artifact('payroll-2025-08.xlsx'),bytes)
  writeFileSync(artifact('payroll-expected.json'),JSON.stringify({workerId:worker.id,code:worker.code,earned:21,pieces:7,paid:10,unpaid:11},null,2))
})

test('API-10 计费规则必填字段缺失应返回400，不能产生服务端500',async({request},testInfo)=>{
  const token=await login(request)
  const boot=await(await request.get('/api/bootstrap',{headers:headers(token)})).json()
  const cases=[
    {name:'缺少模式',rule:{stages:[{key:'main',label:'筐数',type:'PIECE',rate:'3'}]}},
    {name:'空阶段',rule:{mode:'SEGMENTS',stages:[null]}},
    {name:'缺少阶段类型',rule:{mode:'SEGMENTS',stages:[{key:'main',label:'筐数',rate:'3'}]}},
  ]
  const observations=[]
  for(const item of cases){
    for(const path of ['/api/rules/trial','/api/rules']){
      const data=path.endsWith('/trial')?{rule:item.rule,inputs:{quantities:{main:'5'},weightUnit:'kg'}}:{productId:boot.products[0].id,effectiveDate:date(),rule:item.rule,reason:'QA 必填校验',scope:'MONTH'}
      const r=await request.post(path,{headers:headers(token),data})
      observations.push({name:item.name,path,status:r.status(),body:await r.json()})
      expect.soft(r.status(),item.name+' '+path).toBe(400)
    }
  }
  await testInfo.attach('invalid-rule-responses',{body:JSON.stringify(observations,null,2),contentType:'application/json'})
  writeFileSync(artifact('invalid-rule-responses.json'),JSON.stringify(observations,null,2))
})
