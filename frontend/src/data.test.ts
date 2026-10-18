import 'fake-indexeddb/auto'
import {reactive} from 'vue'
import {beforeEach,afterEach,describe,it,expect,vi} from 'vitest'
import {db,session,boot,queue,enqueue,submit,loadQueue,synchronize,initialize,logout,refresh} from './data'
import type {Operation,Bootstrap} from './types'
function network(value:boolean){Object.defineProperty(navigator,'onLine',{value,configurable:true})}
function op(recordId=crypto.randomUUID()):Operation{return {operationId:crypto.randomUUID(),action:'CREATE',recordId,workerId:'worker',productId:'product',workDate:'2026-10-15',period:'AM',inputs:{quantities:{main:'5'},totalWeight:null,weightUnit:'kg'},note:'',version:null,reason:''}}
beforeEach(async()=>{await synchronize();await db.queue.clear();await db.cache.clear();network(false);session.value={token:'token',user:{id:'alice',username:'alice',name:'甲',role:'RECORDER'}};boot.value={user:session.value.user,workers:[],products:[],categories:[],rules:[],today:'2026-10-15'} as Bootstrap;queue.value=[]})
afterEach(()=>vi.restoreAllMocks())
describe('离线队列与同步确认',()=>{
  it('班组及扣皮输入以固定快照保存，响应式表单不会破坏离线队列',async()=>{
    const action=reactive(op());action.entry={mode:'ADD',groupId:'group-a',inputs:{quantities:{main:'15'},totalWeight:null,weightUnit:'jin',weighings:{main:{gross:'32',tare:'2'}}}};
    await enqueue(action);action.entry.inputs.weighings!.main.gross='99';
    const saved=await db.queue.get(action.operationId);expect(saved?.operation.entry?.inputs.weighings?.main.gross).toBe('32');expect(saved?.operation.entry?.groupId).toBe('group-a');
  })
  it('离线退出清除登录状态但保留原账号待同步记录',async()=>{
    const action=op();await enqueue(action);localStorage.setItem('farm-session',JSON.stringify(session.value));await logout();expect(session.value).toBeNull();expect(boot.value).toBeNull();expect(queue.value).toHaveLength(0);expect(localStorage.getItem('farm-session')).toBeNull();expect((await db.queue.get(action.operationId))?.userId).toBe('alice');session.value={token:'token',user:{id:'alice',username:'alice',name:'甲',role:'RECORDER'}};await loadQueue();expect(queue.value).toHaveLength(1)
  })
  it('同步准备期间切换账号，不会把旧账号队列作为新账号发送',async()=>{
    const action=op();await enqueue(action);network(true);const fetcher=vi.spyOn(globalThis,'fetch');const running=synchronize();session.value={token:'bob-token',user:{id:'bob',username:'bob',name:'乙',role:'RECORDER'}};await running;expect(fetcher).not.toHaveBeenCalled();expect(await db.queue.get(action.operationId)).toBeDefined()
  })
  it('未发送本机记录可事务替换，尝试发送后禁止改写未知结果',async()=>{
    const first=op();await enqueue(first);const next={...first,operationId:crypto.randomUUID(),inputs:{...first.inputs,quantities:{main:'8'}}};await enqueue(next,first.operationId);expect(await db.queue.get(first.operationId)).toBeUndefined();expect((await db.queue.get(next.operationId))?.operation.inputs.quantities.main).toBe('8');await db.queue.update(next.operationId,{attempted:true});await expect(enqueue({...next,operationId:crypto.randomUUID()},next.operationId)).rejects.toThrow('已尝试发送');expect(await db.queue.get(next.operationId)).toBeDefined()
  })
  it('本机持久化并按账号隔离，切回原账号仍能读取',async()=>{
    const action=op();await enqueue(action);queue.value=[];expect(await db.queue.count()).toBe(1);await loadQueue();expect(queue.value[0].operation.inputs.quantities.main).toBe('5');session.value!.user.id='bob';await loadQueue();expect(queue.value).toHaveLength(0);session.value!.user.id='alice';await loadQueue();expect(queue.value).toHaveLength(1)
  })
  it('超时或响应丢失后保留原操作ID，明确接收后才删除',async()=>{
    const action=op();await enqueue(action);network(true);const fetcher=vi.spyOn(globalThis,'fetch').mockRejectedValueOnce(new TypeError('网络响应丢失'));
    await synchronize();expect((await db.queue.get(action.operationId))?.state).toBe('pending');
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify([{operationId:action.operationId,result:{status:'ACCEPTED',message:'后台已接收',record:{id:action.recordId,work_date:'2026-10-15',version:1}}}]),{status:200,headers:{'Content-Type':'application/json'}}));await synchronize();expect(await db.queue.count()).toBe(0);const cache=await db.cache.get('alice:records:2026-10');expect(cache?.value).toHaveLength(1);expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))[0].operationId).toBe(action.operationId);expect(JSON.parse(String(fetcher.mock.calls[1][1]?.body))[0].operationId).toBe(action.operationId)
  })
  it('版本冲突保留原输入与服务端最新值，替换失败不丢旧操作',async()=>{
    const action=op();await enqueue(action);network(true);vi.spyOn(globalThis,'fetch').mockResolvedValueOnce(new Response(JSON.stringify([{operationId:action.operationId,result:{status:'VERSION_CONFLICT',message:'版本已变化',record:{id:action.recordId,work_date:'2026-10-15',version:2}}}]),{status:200}));await synchronize();const saved=await db.queue.get(action.operationId);expect(saved?.state).toBe('conflict');expect(saved?.operation.inputs.quantities.main).toBe('5');expect(saved?.result?.record?.version).toBe(2);
    network(false);const occupied=op();await enqueue(occupied);const replacement={...action,operationId:occupied.operationId,action:'UPDATE' as const,version:2};await expect(enqueue(replacement,action.operationId)).rejects.toThrow();expect(await db.queue.get(action.operationId)).toBeDefined()
  })
  it('在线操作直接提交成功，不进入待同步队列',async()=>{
    network(true);const action=op();const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>{await loadQueue();expect(queue.value).toHaveLength(0);return new Response(JSON.stringify({status:'ACCEPTED',message:'后台已接收',record:{id:action.recordId,work_date:action.workDate,version:1}}),{status:200})});
    expect(await submit(action)).toBe('保存成功');expect(fetcher.mock.calls[0][0]).toBe('/api/records/operate');expect(await db.queue.count()).toBe(0);expect((await db.cache.get('alice:records:2026-10'))?.value).toHaveLength(1)
  })
  it('在线明确拒绝保留表单调用方输入，不留下待同步操作',async()=>{
    network(true);const action=op();vi.spyOn(globalThis,'fetch').mockResolvedValueOnce(new Response(JSON.stringify({message:'该记录已经结算'}),{status:409}));await expect(submit(action)).rejects.toThrow('已经结算');expect(action.inputs.quantities.main).toBe('5');expect(await db.queue.count()).toBe(0);expect(queue.value).toHaveLength(0)
  })
  it('在线版本冲突返回最新记录，不伪装成断网加入同步队列',async()=>{
    network(true);const action=op();vi.spyOn(globalThis,'fetch').mockResolvedValueOnce(new Response(JSON.stringify({status:'VERSION_CONFLICT',message:'版本已变化',record:{id:action.recordId,work_date:action.workDate,version:2}}),{status:200}));await expect(submit(action)).rejects.toMatchObject({result:{status:'VERSION_CONFLICT',record:{version:2}}});expect(await db.queue.count()).toBe(0)
  })
  it('在线提交响应丢失后持久化原ID，重连同步不另造操作',async()=>{
    network(true);const action=op();const fetcher=vi.spyOn(globalThis,'fetch').mockRejectedValueOnce(new TypeError('响应丢失'));expect(await submit(action)).toContain('已保存在本机');expect((await db.queue.get(action.operationId))?.attempted).toBe(true);expect(queue.value).toHaveLength(1);
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify([{operationId:action.operationId,result:{status:'ACCEPTED',message:'已接收',record:{id:action.recordId,work_date:action.workDate,version:1}}}]),{status:200}));await synchronize();expect(await db.queue.count()).toBe(0);expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body)).operationId).toBe(JSON.parse(String(fetcher.mock.calls[1][1]?.body))[0].operationId)
  })
  it('在线业务拒绝不会丢失正在替换的原离线冲突',async()=>{
    const original=op();await enqueue(original);await db.queue.update(original.operationId,{state:'conflict',attempted:true});network(true);vi.spyOn(globalThis,'fetch').mockResolvedValueOnce(new Response(JSON.stringify({message:'不允许修改'}),{status:403}));await expect(submit({...original,operationId:crypto.randomUUID()},original.operationId)).rejects.toThrow('不允许修改');expect(await db.queue.get(original.operationId)).toBeDefined();expect(await db.queue.count()).toBe(1)
  })

  it('页面重开恢复中断的在线请求，以原ID补传',async()=>{
    const action=op();await db.queue.put({operationId:action.operationId,userId:'alice',operation:action,state:'sending',message:'发送中',createdAt:Date.now(),attempted:true});await initialize('2026-10');expect(queue.value).toHaveLength(1);expect(queue.value[0].state).toBe('pending');expect(queue.value[0].operationId).toBe(action.operationId);expect(queue.value[0].attempted).toBe(true)
  })
  it('在线提交期间切换账号，回执只写入原账号缓存',async()=>{
    network(true);const action=op();let started!:()=>void,finish!:(response:Response)=>void;const called=new Promise<void>(resolve=>{started=resolve});const response=new Promise<Response>(resolve=>{finish=resolve});const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(()=>{started();return response});const running=submit(action);await called;
    session.value={token:'bob-token',user:{id:'bob',username:'bob',name:'乙',role:'RECORDER'}};queue.value=[];finish(new Response(JSON.stringify({status:'ACCEPTED',message:'成功',record:{id:action.recordId,work_date:action.workDate,version:1}}),{status:200}));await running;expect((fetcher.mock.calls[0][1]?.headers as Record<string,string>).Authorization).toBe('Bearer token');expect((await db.cache.get('alice:records:2026-10'))?.value).toHaveLength(1);expect(await db.cache.get('bob:records:2026-10')).toBeUndefined();expect(queue.value).toHaveLength(0)
  })


  it('移除派工后不会上传旧派工队列，旧本机数据保留',async()=>{
    const key='alice:dispatch-op:retired';await db.cache.put({key,value:{operation:{id:'old-plan'},state:'pending'}});
    network(true);const fetcher=vi.spyOn(globalThis,'fetch');await synchronize();expect(fetcher).not.toHaveBeenCalled();expect(await db.cache.get(key)).toBeDefined();
  })

  it('较早发出的刷新不能覆盖刚提交成功的新版本',async()=>{
    network(true);const action=op();let finish!:(response:Response)=>void;
    const delayed=new Promise<Response>(resolve=>{finish=resolve});
    vi.spyOn(globalThis,'fetch').mockImplementation(async(path)=>String(path).includes('/bootstrap')?new Response(JSON.stringify(boot.value),{status:200}):String(path).includes('/records?')?delayed:new Response(JSON.stringify({status:'ACCEPTED',message:'已接收',record:{id:action.recordId,work_date:action.workDate,version:2}}),{status:200}));
    const loading=refresh('2026-10');await submit(action);finish(new Response(JSON.stringify([{id:action.recordId,work_date:action.workDate,version:1}]),{status:200}));await loading;
    expect(((await db.cache.get('alice:records:2026-10'))?.value as any[])[0].version).toBe(2)
  })
})
