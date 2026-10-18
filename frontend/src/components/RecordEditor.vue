<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {Save,X,Sun,Sunset} from 'lucide-vue-next'
import {boot,records,activeRule,localDate,submit,OperationRejected,queue,online,session} from '../data'
import type {Harvest,Inputs,Operation,SyncResult,Weighing} from '../types'
import WeightField from './WeightField.vue'
const props=defineProps<{record?:Harvest|null;preserved?:Operation|null;conflictId?:string;mode?:'ADD'|'CORRECT'|'MOVE';continuous?:boolean}>()
const emit=defineEmits<{close:[];saved:[message:string,keepOpen?:boolean]}>()
const empty=():Inputs=>({quantities:{},totalWeight:null,weightUnit:'kg',weighings:{}})
const worker=ref(''),product=ref(''),date=ref(localDate()),period=ref<'AM'|'PM'>(new Date().getHours()<12?'AM':'PM'),group=ref(''),plot=ref(''),targetPlot=ref(''),source=ref(''),note=ref(''),reason=ref(''),error=ref(''),busy=ref(false),search=ref(''),onlyGroup=ref(false)
const inputs=ref<Inputs>(empty()),currentVersion=ref<number|null>(null),conflict=ref<SyncResult|null>(null),latest=ref<Harvest|null>(null),cycle=ref(0)
const mode=computed(()=>props.preserved?(props.preserved.entry?.mode||(props.preserved.action==='CREATE'?'ADD':'CORRECT')):props.mode||(props.record?'CORRECT':'ADD'))
const matched=computed(()=>props.record||records.value.find(r=>r.worker_id===worker.value&&r.product_id===product.value&&r.work_date===date.value&&r.period===period.value)||null)
const target=computed(()=>latest.value||matched.value)
const rule=computed(()=>activeRule(product.value,date.value)),late=computed(()=>date.value<localDate())
const workers=computed(()=>boot.value?.workers.filter(w=>(w.active||w.id===worker.value)&&(w.name+w.code).includes(search.value)&&(!onlyGroup.value||(w.group_id||'')===group.value))||[])
const compositions=computed(()=>target.value?.allocations?.length?target.value.allocations:[{group_id:target.value?.groups?.[0]?.group_id||'',plot_id:'',group_name:target.value?.groups?.[0]?.group_name||'未分组',plot_name:'未指定地块',inputs_json:target.value?.inputs_json||'{}'}])
function sourceKey(g:string,p:string){return JSON.stringify([g,p])}
function selectSource(){const row=compositions.value.find(r=>sourceKey(r.group_id,r.plot_id)===source.value);if(row){group.value=row.group_id;plot.value=row.plot_id}}
watch(source,selectSource)
const groupOptions=computed(()=>(boot.value?.groups||[]).filter(g=>g.active||mode.value==='CORRECT'&&target.value?.groups?.some(r=>r.group_id===g.id)))
function loadGroup(){
  if(mode.value!=='CORRECT'){inputs.value=empty();cycle.value++;return}
  const r=target.value;if(!r)return
  const g=r.allocations?.find(g=>g.group_id===group.value&&g.plot_id===plot.value)||(!r.allocations?.length&&plot.value===''?r.groups?.find(g=>g.group_id===group.value):undefined)
  inputs.value=g?JSON.parse(g.inputs_json):!r.groups?.length&&group.value===''?JSON.parse(r.inputs_json):empty()
  inputs.value.weighings={};cycle.value++
}
watch(()=>props.record,r=>{
  if(r){worker.value=r.worker_id;product.value=r.product_id;date.value=r.work_date;period.value=r.period;note.value=r.note;currentVersion.value=r.version;group.value=r.allocations?.[0]?.group_id||r.groups?.[0]?.group_id||'';plot.value=r.allocations?.[0]?.plot_id||'';source.value=sourceKey(group.value,plot.value);loadGroup()}
  if(props.preserved){const p=props.preserved;if(!r){worker.value=p.workerId;product.value=p.productId;date.value=p.workDate;period.value=p.period}group.value=p.entry?.groupId||'';plot.value=p.entry?.plotId||'';targetPlot.value=p.entry?.targetPlotId||'';if(mode.value==='MOVE'){source.value=sourceKey(p.entry?.groupId||'',p.entry?.plotId||'');group.value=p.entry?.targetGroupId??p.entry?.groupId??'';plot.value=p.entry?.targetPlotId??p.entry?.plotId??'';}inputs.value=JSON.parse(JSON.stringify(p.entry?.inputs||p.inputs));inputs.value.weighings??={};note.value=p.note;reason.value=p.reason}
},{immediate:true})
watch([group,plot],loadGroup,{flush:'sync'})
watch([product,date],()=>{if(!props.record&&!props.preserved){inputs.value=empty();latest.value=null;cycle.value++}})
watch([worker,product,date,period],()=>{if(!props.record){latest.value=null;currentVersion.value=matched.value?.version??null;if(!props.preserved)note.value=matched.value?.note||''}})
function capture(key:string,w:Weighing|undefined){inputs.value.weighings??={};if(w)inputs.value.weighings[key]=w;else delete inputs.value.weighings[key]}
function resolveConflict(keep:boolean){const r=conflict.value?.record;if(!r)return;latest.value=r;currentVersion.value=r.version;if(!keep)loadGroup();conflict.value=null;error.value=''}
const summary=computed(()=>target.value?.quote?.parts.map(p=>`${p.quantity}${p.type==='PIECE'?'筐':'公斤'}`).join(' / '))
async function save(){
  error.value='';if(conflict.value){error.value='请先核对最新记录';return}
  if(!worker.value||!product.value||!rule.value){error.value='请选择工人、产品和有规则的日期';return}
  if(target.value?.deleted){error.value='原记录已删除，请先在账本恢复原记录';return}
  if(mode.value!=='ADD'&&!reason.value.trim()){error.value='更正数量必须填写原因';return}
  if(late.value&&!target.value&&!note.value.trim()){error.value='往日补录必须填写说明';return}
  if(mode.value==='MOVE'&&(!target.value||source.value===sourceKey(group.value,plot.value))){error.value='请修改所属班组或地块';return}
  if(date.value>localDate()){error.value='不能录入未来日期';return}
  if(queue.value.some(q=>q.operationId!==props.conflictId&&q.operation.workerId===worker.value&&q.operation.productId===product.value&&q.operation.workDate===date.value&&q.operation.period===period.value)){error.value='本机已有相同时段记录待处理，请先同步或核对';return}
  busy.value=true
  try{
    const clean:Inputs={...inputs.value,quantities:Object.fromEntries(Object.entries(inputs.value.quantities).filter(([key])=>rule.value?.stages.some(s=>s.key===key)))}
    const r=target.value
    const operation:Operation={operationId:crypto.randomUUID(),action:r?'UPDATE':'CREATE',recordId:r?.id||props.preserved?.recordId||crypto.randomUUID(),workerId:worker.value,workDate:date.value,period:period.value,productId:product.value,inputs:clean,note:note.value,version:currentVersion.value??r?.version??null,reason:reason.value,entry:{mode:mode.value,groupId:group.value,plotId:plot.value,...(mode.value==='MOVE'?{groupId:JSON.parse(source.value)[0],plotId:JSON.parse(source.value)[1],targetGroupId:group.value,targetPlotId:plot.value}:{}),inputs:clean}}
    const message=await submit(operation,props.conflictId)
    const stay=!!props.continuous&&!props.record&&!props.conflictId
    if(stay){worker.value='';search.value='';inputs.value=empty();note.value='';reason.value='';currentVersion.value=null;latest.value=null;cycle.value++}
    emit('saved',message,stay)
  }catch(e){error.value='未保存：'+(e as Error).message;if(e instanceof OperationRejected&&e.result.status==='VERSION_CONFLICT')conflict.value=e.result}
  finally{busy.value=false}
}
</script>
<template><div class="modal-shade" @click.self="emit('close')"><section class="modal editor" role="dialog" aria-modal="true" aria-labelledby="editor-title">
  <div class="modal-head"><div><p class="eyebrow">田间采摘 · {{continuous?'连续录入':mode==='MOVE'?'更正归属':mode==='CORRECT'?'更正数量':'增加采摘量'}}</p><h2 id="editor-title">{{mode==='MOVE'?'更正班组与地块归属':mode==='CORRECT'?'编辑采摘记录':target?'增加采摘量':'记一笔采摘'}}</h2></div><button class="icon-button" aria-label="关闭" @click="emit('close')"><X/></button></div>
  <p v-if="target" class="notice">原记录总量：{{summary||'待核对'}}。{{mode==='ADD'?'只填写本次增加量，保存后累计到原记录。':'核对所选数量组成，其他组成保持不变。'}}</p>
  <p v-if="target?.settled" class="notice">该记录已有账目快照，更正必须填写原因，原账单保留，应发与未付会按最新数量更新。</p>
  <form @submit.prevent="save"><div class="form-grid"><label>实际采摘日期<input v-model="date" type="date" :max="localDate()" :disabled="!!record" required/></label><fieldset class="period-field"><legend>采摘时段</legend><div class="segmented"><button type="button" :disabled="!!record" :class="{selected:period==='AM'}" @click="period='AM'"><Sun :size="17"/>上午</button><button type="button" :disabled="!!record" :class="{selected:period==='PM'}" @click="period='PM'"><Sunset :size="17"/>下午</button></div></fieldset></div>
    <label v-if="mode==='MOVE'&&compositions.length>1">选择要更正的数量组成<select v-model="source" aria-label="原数量组成"><option v-for="c in compositions" :key="sourceKey(c.group_id,c.plot_id)" :value="sourceKey(c.group_id,c.plot_id)">{{c.group_name}} · {{c.plot_name}}</option></select></label>
    <label>本次所属班组<select v-model="group" aria-label="本次所属班组"><option value="">未分组</option><option v-for="g in groupOptions" :key="g.id" :value="g.id">{{g.name}}</option></select></label>
    <label>本次地块 / 大棚<select v-model="plot" aria-label="本次地块"><option value="">未指定地块</option><option v-for="p in boot?.plots?.filter(p=>p.active||target?.allocations?.some(a=>a.plot_id===p.id))" :key="p.id" :value="p.id">{{p.name}}</option></select></label>
    <p v-if="!plot" class="notice">本次数量暂未指定地块，请后续核对补齐。</p>
    <p v-if="mode==='MOVE'" class="notice">直接修改上方所属班组或地块，只转移选中的数量组成；总数量、工资不变。仅改地块无需重新签字。</p>
    <template v-if="!record"><div class="form-grid"><label>查找工人<input v-model="search" placeholder="输入姓名或工号"/></label><label class="check-label"><input v-model="onlyGroup" type="checkbox"/>只看本组工人</label></div></template>
    <div class="form-grid"><label>工人<select aria-label="工人" v-model="worker" :disabled="!!record" required><option value="" disabled>选择工人</option><option v-for="w in workers" :key="w.id" :value="w.id">{{w.code}} · {{w.name}}</option></select></label><label>产品<select aria-label="产品" v-model="product" :disabled="!!record" required><option value="" disabled>选择产品</option><optgroup v-for="c in boot?.categories.filter(c=>c.active)" :key="c.id" :label="c.name"><option v-for="p in boot?.products.filter(p=>p.active&&p.category_id===c.id)" :key="p.id" :value="p.id">{{p.name}}</option></optgroup></select></label></div>
    <section v-if="rule&&mode!=='MOVE'" class="quantity-panel"><h3>{{mode==='ADD'?'本次增加的工作量':'本班组、本地块正确累计工作量'}}</h3><p class="muted">工资按整个时段的总量应用产品规则，班组数量不单独计薪。</p>
      <label v-if="rule.mode==='ALLOCATE'||rule.stages.some(s=>s.type==='WEIGHT')">重量单位<select aria-label="重量单位" v-model="inputs.weightUnit"><option value="kg">公斤</option><option value="jin">斤</option><option value="g">克</option></select></label>
      <WeightField v-if="rule.mode==='ALLOCATE'" :key="'total'+cycle" label="采摘总重量" :unit="inputs.weightUnit" v-model="inputs.totalWeight" :capture="inputs.weighings?.totalWeight" @update:capture="capture('totalWeight',$event)"/>
      <template v-else><div v-for="s in rule.stages" :key="s.key+cycle"><WeightField v-if="s.type==='WEIGHT'" :label="s.label" :unit="inputs.weightUnit" v-model="inputs.quantities[s.key]" :capture="inputs.weighings?.[s.key]" @update:capture="capture(s.key,$event)"/><label v-else>{{s.label}}（筐）<input :aria-label="s.label" v-model="inputs.quantities[s.key]" type="number" min="0" step="0.5" inputmode="decimal" placeholder="筐数"/></label></div></template>
    </section><p v-else-if="product&&!rule" class="notice danger">该日期没有可用的产品计费规则，请联系老板配置。</p>
    <label>{{late&&!target?'补录说明（必填）':'备注（选填）'}}<textarea v-model="note" :required="late&&!target" maxlength="1000" rows="2"/></label>
    <label v-if="target||mode==='CORRECT'">修改原因<input v-model="reason" maxlength="1000" :required="mode!=='ADD'||!!target?.settled||(session?.user.role==='ADMIN'&&late)" placeholder="说明更正或增加情况"/></label>
    <div v-if="conflict?.record" class="notice" role="alert"><p>该记录已被【{{conflict.record.updated_name}}】更新，当前最新数量为 {{conflict.record.quote?.parts.map(p=>`${p.quantity}${p.type==='PIECE'?'筐':'公斤'}`).join(' / ')}}。输入仍保留，请核对。</p><div class="button-row"><button type="button" @click="resolveConflict(false)">以最新数量重新修改</button><button type="button" @click="resolveConflict(true)">保留我的输入，核对后重提</button></div></div>
    <p v-if="error" class="notice danger" role="alert">{{error}}</p><div class="modal-actions"><p class="muted">{{online?'联网直接提交；连接失败时保存本机。':'离线保存，联网后自动同步。'}}</p><button class="primary" :disabled="busy"><Save :size="18"/>{{busy?'保存中…':'保存记录'}}</button></div>
  </form></section></div></template>
