import Dexie, {type Table} from 'dexie'
import {ref,computed} from 'vue'
import type {Actor,Bootstrap,Harvest,Pending,Operation,SyncResult,Rule} from './types'

class LocalLedger extends Dexie {
  queue!:Table<Pending,string>; cache!:Table<{key:string;value:unknown},string>
  constructor(){super('farm-ledger-v1');this.version(1).stores({queue:'operationId,userId,state,createdAt',cache:'key'})}
}
export const db=new LocalLedger()
export const session=ref<{token:string;user:Actor}|null>(JSON.parse(localStorage.getItem('farm-session')||'null'))
export const boot=ref<Bootstrap|null>(null)
export const records=ref<Harvest[]>([])
export const queue=ref<Pending[]>([])
export const online=ref(navigator.onLine)
export const reachable=ref(true)
export const syncing=ref(false)
export const syncError=ref('')
export const lastSync=ref('')
export const pendingCount=computed(()=>queue.value.filter(q=>q.state==='pending').length)
export const conflictCount=computed(()=>queue.value.filter(q=>q.state!=='pending').length)
export class HttpError extends Error {constructor(public status:number,message:string){super(message)}}
export class OperationRejected extends Error {constructor(public result:SyncResult){super(result.message)}}
const activeSubmissions=new Set<string>()
let refreshGeneration=0
function mergeRecords(incoming:Harvest[],cached:Harvest[]){const merged=new Map(incoming.map(r=>[r.id,r]));for(const r of cached){const fresh=merged.get(r.id);if(!fresh||r.version>fresh.version)merged.set(r.id,r)}return [...merged.values()]}
export function localDate(){const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`}
export function activeRule(product:string,date:string):Rule|null {
  const rows=boot.value?.rules.filter(r=>r.product_id===product&&r.effective_date<=date&&(!r.end_date||r.end_date>=date)).sort((a,b)=>b.revision-a.revision)||[]
  return rows[0]?JSON.parse(rows[0].rule_json):null
}
export async function api<T=any>(path:string,options:RequestInit={}):Promise<T>{
  const response=await fetch('/api'+path,{...options,headers:{'Content-Type':'application/json',...(session.value?{Authorization:'Bearer '+session.value.token}:{}),...options.headers},signal:options.signal??AbortSignal.timeout(12000)})
  reachable.value=true
  if(!response.ok){const error=await response.json().catch(()=>({message:'请求失败'}));if(response.status===401&&session.value)syncError.value='登录已过期，请重新登录；待同步记录仍保留';throw new HttpError(response.status,error.message||'请求失败')}
  return response.json()
}
export async function loadQueue(){const uid=session.value?.user.id;const items=uid?await db.queue.where('userId').equals(uid).filter(q=>q.state!=='sending').sortBy('createdAt'):[];if(session.value?.user.id===uid)queue.value=items}
export async function initialize(month:string){
  if(!session.value)return
  const uid=session.value.user.id
  // Recover interrupted online requests with their original IDs after a reload.
  await db.queue.where('userId').equals(uid).filter(q=>q.state==='sending'&&!activeSubmissions.has(q.operationId)).modify({state:'pending',message:'上次提交结果尚未确认，等待自动同步'})
  const cached=await db.cache.get(uid+':bootstrap');if(session.value?.user.id!==uid)return;if(cached)boot.value=cached.value as Bootstrap
  const saved=await db.cache.get(uid+':records:'+month);records.value=(saved?.value as Harvest[])||[]
  const last=await db.cache.get(uid+':lastSync');lastSync.value=String(last?.value||'')
  await loadQueue()
  if(navigator.onLine){try{await refresh(month);await synchronize()}catch(e){reachable.value=false;syncError.value=(e as Error).message}}
}
export async function refresh(month:string){
  const current=session.value;if(!current)return
  const generation=++refreshGeneration
  const [b,r]=await Promise.all([api<Bootstrap>('/bootstrap'),api<Harvest[]>('/records?month='+month)])
  if(session.value?.user.id!==current.user.id||generation!==refreshGeneration)return
  await db.transaction('rw',db.cache,async()=>{await db.cache.put({key:current.user.id+':bootstrap',value:b});const key=current.user.id+':records:'+month;const saved=await db.cache.get(key);const merged=mergeRecords(r,(saved?.value as Harvest[])||[]);await db.cache.put({key,value:merged});if(session.value?.user.id===current.user.id&&generation===refreshGeneration)records.value=mergeRecords(merged,records.value.filter(row=>row.work_date.startsWith(month)))})
  if(session.value?.user.id!==current.user.id||generation!==refreshGeneration)return
  boot.value=b;syncError.value='';reachable.value=true;window.dispatchEvent(new Event('farm-refreshed'))
  if(navigator.storage?.persist)void navigator.storage.persist()
}
async function storeOperation(operation:Operation,replaces?:string,state:Pending['state']='pending'){
  if(!session.value||!boot.value)throw new Error('请先联网登录并下载资料')
  const uid=session.value.user.id
  const prior=await db.transaction('rw',db.queue,async()=>{
    const existing=await db.queue.where('userId').equals(uid).toArray()
    if(replaces){const prior=existing.find(q=>q.operationId===replaces);if(!prior||prior.state==='sending'||(prior.state==='pending'&&prior.attempted))throw new Error('该操作已尝试发送，请先同步确认后再修改');await db.queue.delete(replaces)}
    if(existing.some(q=>q.operationId!==replaces&&q.operation.recordId===operation.recordId))throw new Error('这条记录有尚未处理的本机操作，请先同步或处理冲突')
    await db.queue.add({operationId:operation.operationId,userId:uid,operation,state,message:'已保存在本机，待同步',createdAt:Date.now(),attempted:state==='sending'});
    return existing.find(q=>q.operationId===replaces)
  })
  return prior
}
export async function enqueue(operation:Operation,replaces?:string){
  operation=JSON.parse(JSON.stringify(operation))
  await storeOperation(operation,replaces)
  await loadQueue();void synchronize()
}
async function cacheRecord(uid:string,record?:Harvest){
  if(!record)return
  refreshGeneration++
  const key=uid+':records:'+record.work_date.slice(0,7);const cache=await db.cache.get(key);const list=(cache?.value as Harvest[])||[]
  await db.cache.put({key,value:mergeRecords([record],list)})
}
export async function submit(operation:Operation,replaces?:string):Promise<string>{
  // Freeze nested form metadata as a plain payload before IndexedDB or any await.
  operation=JSON.parse(JSON.stringify(operation))
  if(!navigator.onLine){await enqueue(operation,replaces);return '已保存在本机，联网后自动同步'}
  const current=session.value;if(!current)throw new Error('请先登录')
  const uid=current.user.id
  activeSubmissions.add(operation.operationId)
  let prior:Pending|undefined
  try{
    // Keep a crash-recovery receipt locally; normal online submissions never appear in the sync queue.
    prior=await storeOperation(operation,replaces,'sending')
    let result:SyncResult
    try{result=await api<SyncResult>('/records/operate',{method:'POST',headers:{Authorization:'Bearer '+current.token},body:JSON.stringify(operation)})}
    catch(e){
      if(e instanceof HttpError&&e.status>=400&&e.status<500)throw e
      reachable.value=false
      await db.queue.update(operation.operationId,{state:'pending',message:'提交结果尚未确认，等待自动同步'})
      await loadQueue()
      return '暂时无法确认提交结果，已保存在本机，稍后自动同步'
    }
    if(result.status!=='ACCEPTED')throw new OperationRejected(result)
    await db.transaction('rw',db.cache,db.queue,async()=>{await cacheRecord(uid,result.record);await db.queue.delete(operation.operationId)})
    if(session.value?.user.id===uid)await loadQueue()
    return operation.action==='DELETE'?'删除成功':operation.action==='RESTORE'?'恢复成功':'保存成功'
  }catch(e){
    if(e instanceof HttpError||e instanceof OperationRejected){
      await db.transaction('rw',db.cache,db.queue,async()=>{await db.queue.delete(operation.operationId);if(prior)await db.queue.put(prior);if(e instanceof OperationRejected)await cacheRecord(uid,e.result.record)})
      await loadQueue()
    }
    throw e
  }finally{activeSubmissions.delete(operation.operationId)}
}
let inFlight:Promise<void>|null=null
export function synchronize(){
  if(!session.value||!navigator.onLine)return Promise.resolve()
  if(inFlight)return inFlight
  inFlight=runSync().finally(()=>{inFlight=null});return inFlight
}
async function runSync(){
  if(!session.value||!navigator.onLine)return
  const current=session.value;const uid=current.user.id
  async function work(){
    if(session.value?.user.id!==uid)return
    syncing.value=true;syncError.value=''
    try{
      const pending=await db.transaction('rw',db.queue,async()=>{const list=(await db.queue.where('userId').equals(uid).filter(q=>q.state==='pending').sortBy('createdAt')).slice(0,100);for(const item of list)await db.queue.update(item.operationId,{attempted:true});return list})
      if(!pending.length||session.value?.user.id!==uid)return
      const result=await api<{operationId:string;result:SyncResult}[]>('/sync',{method:'POST',headers:{Authorization:'Bearer '+current.token},body:JSON.stringify(pending.slice(0,100).map(q=>q.operation))})
      for(const item of result){
        const row=pending.find(p=>p.operationId===item.operationId);if(!row)continue
        if(item.result.status==='ACCEPTED'){
          const record=item.result.record
          await db.transaction('rw',db.cache,db.queue,async()=>{
            if(record){refreshGeneration++;const key=uid+':records:'+record.work_date.slice(0,7);const cache=await db.cache.get(key);const list=(cache?.value as Harvest[])||[];await db.cache.put({key,value:mergeRecords([record],list)})}
            await db.queue.delete(item.operationId)
          })
        }else await db.queue.update(item.operationId,{state:item.result.status==='REJECTED'?'rejected':'conflict',message:item.result.message,result:item.result})
      }
      lastSync.value=new Date().toLocaleString('zh-CN');await db.cache.put({key:uid+':lastSync',value:lastSync.value});reachable.value=true
      window.dispatchEvent(new Event('farm-synced'))
    }catch(e){syncError.value=(e as Error).message;if(!syncError.value.includes('登录'))reachable.value=false}
    finally{if(session.value?.user.id===uid)await loadQueue();syncing.value=false}
  }
  if(navigator.locks)await navigator.locks.request('farm-sync-'+uid,work);else await work()
}
export async function discard(id:string){await db.queue.delete(id);await loadQueue()}
export async function login(username:string,password:string){
  const current=await api<{token:string;user:Actor}>('/login',{method:'POST',body:JSON.stringify({username,password})});session.value=current;localStorage.setItem('farm-session',JSON.stringify(current));boot.value=null;records.value=[];queue.value=[]
}
export async function logout(){
  const current=session.value
  localStorage.removeItem('farm-session');session.value=null;boot.value=null;records.value=[];queue.value=[]
  lastSync.value='';syncError.value=''
  try{if(current&&navigator.onLine)await fetch('/api/logout',{method:'POST',headers:{Authorization:'Bearer '+current.token},signal:AbortSignal.timeout(3000)})}catch{/* Offline logout preserves queued operations for the original account. */}
}
window.addEventListener('online',()=>{online.value=true;void synchronize()})
window.addEventListener('offline',()=>{online.value=false;reachable.value=false})
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'){online.value=navigator.onLine;void synchronize()}})
setInterval(()=>{if(document.visibilityState==='visible')void synchronize()},15000)

