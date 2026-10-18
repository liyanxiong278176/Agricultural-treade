<script setup lang="ts">
import {computed,ref,watch,onMounted,onBeforeUnmount} from 'vue'
import {api,online,reachable} from '../data'
const props=withDefaults(defineProps<{rows?:any[];endpoint?:string;params?:Record<string,any>;searchLabel?:string;showSearch?:boolean;refreshKey?:any;scope?:string}>(),{rows:()=>[],params:()=>({}),searchLabel:'查询关键字',showSearch:true,scope:'数据列表'})
const emit=defineEmits<{loaded:[result:any];filtered:[rows:any[]];reset:[]}>()
const query=ref(''),page=ref(1),size=ref(20),remote=ref<any[]>([]),total=ref(0),busy=ref(false),error=ref(''),fallback=ref(false)
let generation=0,timer:ReturnType<typeof setTimeout>|undefined
const filtered=computed(()=>props.rows.filter(row=>!query.value.trim()||JSON.stringify(row).toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())))
const local=computed(()=>!props.endpoint||!online.value||!reachable.value||fallback.value)
const count=computed(()=>local.value?filtered.value.length:total.value)
const pages=computed(()=>Math.max(1,Math.ceil(count.value/size.value)))
const items=computed(()=>local.value?filtered.value.slice((page.value-1)*size.value,page.value*size.value):remote.value)
async function load(){
  const version=++generation;error.value='';fallback.value=false
  if(!props.endpoint||!online.value||!reachable.value){busy.value=false;return}
  busy.value=true
  try{
    const params=new URLSearchParams(Object.entries({...props.params,q:query.value||props.params.q||'',page:page.value,size:size.value}).filter(([,value])=>value!==undefined&&value!==null).map(([key,value])=>[key,String(value)]))
    const result=await api(props.endpoint+'?'+params)
    if(version!==generation)return
    if(!Array.isArray(result.items)||!Number.isSafeInteger(result.total)||result.total<0)throw new Error('列表数据格式无效，请刷新后重试')
    remote.value=result.items;total.value=result.total;emit('loaded',result)
    if(page.value>Math.max(1,Math.ceil(result.total/size.value))){page.value=Math.max(1,Math.ceil(result.total/size.value));return}
  }catch(e){if(version===generation){error.value=(e as Error).message;fallback.value=true}}
  finally{if(version===generation)busy.value=false}
}
function reset(){query.value='';page.value=1;emit('reset');void load()}
watch(()=>props.endpoint,()=>{query.value='';page.value=1})
watch([query,()=>props.endpoint,()=>JSON.stringify(props.params)],()=>{page.value=1;remote.value=[];total.value=0;busy.value=!!props.endpoint&&online.value&&reachable.value;clearTimeout(timer);timer=setTimeout(load,180)})
watch([page,size,online,reachable,()=>props.refreshKey],()=>{void load()})
watch(()=>props.rows,()=>{if(page.value>pages.value)page.value=pages.value;if(props.endpoint)void load()},{deep:true})
watch(filtered,rows=>emit('filtered',rows),{immediate:true})
void load()
onMounted(()=>window.addEventListener('farm-refreshed',load))
onBeforeUnmount(()=>{generation++;clearTimeout(timer);window.removeEventListener('farm-refreshed',load)})
defineExpose({reload:load,allRows:filtered,query})
</script>
<template><section class="data-list" :aria-label="scope">
  <div v-if="showSearch" class="list-query no-print"><label>{{searchLabel}}<input v-model="query" :aria-label="searchLabel" maxlength="100" placeholder="输入名称、工号或关键字" @keydown.enter.prevent="load"/></label><slot name="filters"/><button :disabled="busy" @click="load">查询</button><button @click="reset">重置</button></div>
  <p v-if="endpoint&&local" class="notice no-print">{{!online||!reachable?'离线查询仅包含本机已下载的数据。':'服务暂不可达，当前显示本机已下载数据。'}}</p>
  <p v-if="error" class="notice danger no-print" role="alert">{{error}}</p>
  <p v-if="busy" class="muted no-print" role="status">正在查询…</p>
  <slot :items="items" :total="count" :busy="busy"/>
  <p v-if="!items.length&&!busy" class="muted list-empty no-print">没有符合查询条件的数据。</p>
  <div class="pagination no-print" :aria-label="scope+'分页'"><span>共 {{count}} 条 · 第 {{page}} / {{pages}} 页</span><label>每页<select v-model.number="size" aria-label="每页条数"><option :value="10">10条</option><option :value="20">20条</option><option :value="50">50条</option></select></label><button :disabled="page<=1||busy" @click="page--">上一页</button><button :disabled="page>=pages||busy" @click="page++">下一页</button></div>
</section></template>
