import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,type App} from 'vue'
vi.mock('../data',async()=>{const {ref}=await import('vue');return {api:vi.fn(),online:ref(true),reachable:ref(true)}})
import {api,online,reachable} from '../data'
import DataList from './DataList.vue'
let app:App,host:HTMLElement
function mount(props:any){host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(DataList,props,{default:({items}:any)=>h('ul',items.map((row:any)=>h('li',row.name)))})});app.mount(host)}
beforeEach(()=>{vi.useFakeTimers();vi.mocked(api).mockReset();online.value=true;reachable.value=true})
afterEach(()=>{app?.unmount();host?.remove();vi.useRealTimers()})
describe('列表查询分页',()=>{
  it('本机列表翻页后查询回到第一页，操作与总数保持一致',async()=>{
    mount({rows:Array.from({length:25},(_,i)=>({name:'工人'+String(i).padStart(2,'0')}))});await nextTick()
    expect(host.querySelectorAll('li')).toHaveLength(20)
    ;(host.querySelector('.pagination button:last-child') as HTMLButtonElement).click();await nextTick();expect(host.querySelectorAll('li')).toHaveLength(5)
    const input=host.querySelector('input')!;input.value='工人24';input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()
    expect(host.querySelectorAll('li')).toHaveLength(1);expect(host.querySelector('.pagination')?.textContent).toContain('共 1 条');expect(host.querySelector('.pagination')?.textContent).toContain('第 1 / 1 页')
  })
  it('先发后到的响应不能覆盖新的查询结果',async()=>{
    let oldResolve!:(value:any)=>void
    vi.mocked(api).mockImplementationOnce(()=>new Promise(resolve=>{oldResolve=resolve})).mockResolvedValue({items:[{name:'新结果'}],total:1})
    mount({endpoint:'/query/catalog/workers'})
    const input=host.querySelector('input')!;input.value='新';input.dispatchEvent(new Event('input',{bubbles:true}));await vi.advanceTimersByTimeAsync(200);await nextTick()
    expect(host.querySelector('li')?.textContent).toBe('新结果')
    oldResolve({items:[{name:'旧结果'}],total:50});await nextTick();await nextTick();expect(host.querySelector('li')?.textContent).toBe('新结果')
  })
  it('断网时只查询已下载数据，并明确告知范围',async()=>{
    online.value=false;mount({endpoint:'/query/records',rows:[{name:'本机记录'}]});await nextTick()
    expect(api).not.toHaveBeenCalled();expect(host.textContent).toContain('仅包含本机已下载');expect(host.querySelector('li')?.textContent).toBe('本机记录')
  })
  it('修改查询条件后不继续展示上一页的数据',async()=>{
    let finish!:(result:any)=>void
    vi.mocked(api).mockResolvedValueOnce({items:[{name:'原列表'}],total:1}).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
    mount({endpoint:'/query/catalog/workers'});await nextTick();await nextTick();expect(host.querySelector('li')?.textContent).toBe('原列表')
    const input=host.querySelector('input')!;input.value='新查询';input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick();expect(host.querySelectorAll('li')).toHaveLength(0);expect(host.textContent).toContain('正在查询')
    await vi.advanceTimersByTimeAsync(200);finish({items:[{name:'新列表'}],total:1});await nextTick();await nextTick();expect(host.querySelector('li')?.textContent).toBe('新列表')
  })
  it('服务端列表响应格式不完整时显示错误并保留本机数据',async()=>{
    vi.mocked(api).mockResolvedValue({status:'ok'});mount({endpoint:'/query/records',rows:[{name:'本机记录'}]});await nextTick();await nextTick()
    expect(host.textContent).toContain('列表数据格式无效');expect(host.querySelector('li')?.textContent).toBe('本机记录')
  })
})
