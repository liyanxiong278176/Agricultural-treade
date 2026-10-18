import {afterEach,describe,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,type App} from 'vue'
vi.mock('../data',async()=>{const {ref}=await import('vue');return {boot:ref({grossWeights:[{id:'gross',name:'预设毛重',weight_kg:1.234567,active:true,revision:1}],baskets:[{id:'basket',name:'预设皮重',weight_kg:0.000123,active:true,revision:1}]})}})
import WeightField from './WeightField.vue'
let app:App,host:HTMLElement
afterEach(()=>{app?.unmount();host?.remove()})
describe('配置重量换算',()=>{
  it.each([['g','1234.567','0.123','1234.198'],['jin','2.469134','0.000246','2.468396']])('%s录入保持六位小数协议，净重只读且在扣皮卡片下方',async(unit,gross,tare,net)=>{
    const captures:any[]=[];host=document.createElement('div');document.body.append(host)
    app=createApp({render:()=>h(WeightField,{label:'采摘重量',unit,modelValue:null,'onUpdate:capture':(capture:any)=>captures.push(capture)})});app.mount(host)
    const checkbox=host.querySelector<HTMLInputElement>('input[type="checkbox"]')!;checkbox.checked=true;checkbox.dispatchEvent(new Event('change',{bubbles:true}));await nextTick()
    const choose=async(label:string,value:string)=>{const select=host.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)!;select.value=value;select.dispatchEvent(new Event('change',{bubbles:true}));await nextTick()}
    await choose('毛重配置','gross');await choose('筐型','basket')
    const count=host.querySelector<HTMLInputElement>('input[aria-label="实际容器数量"]')!;count.value='3';count.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()
    const actual=host.querySelector<HTMLInputElement>('input[aria-label="实际重量"]')!;expect(actual.readOnly).toBe(true);expect(actual.value).toBe(net)
    const capture=captures.at(-1);expect(capture.gross).toBe(gross);expect(capture.basketWeight).toBe(tare);expect(capture.count).toBe(3);expect(capture.grossRevision).toBe(1);expect(capture.basketRevision).toBe(1)
    expect(host.querySelector('.tare-card')!.compareDocumentPosition(actual)&Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})
