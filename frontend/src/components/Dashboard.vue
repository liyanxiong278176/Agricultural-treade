<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref,watch,nextTick} from 'vue'
import * as echarts from 'echarts/core'
import {BarChart,PieChart,LineChart} from 'echarts/charts'
import {GridComponent,TooltipComponent,LegendComponent} from 'echarts/components'
import {CanvasRenderer} from 'echarts/renderers'
import {Sprout,NotebookPen,Users,Coins,ArrowUpRight} from 'lucide-vue-next'
import {records,boot} from '../data'
echarts.use([BarChart,PieChart,LineChart,GridComponent,TooltipComponent,LegendComponent,CanvasRenderer])
defineProps<{month:string}>()
const emit=defineEmits<{navigate:[page:string];create:[]}>()
const valid=computed(()=>records.value.filter(r=>!r.deleted))
const amount=computed(()=>valid.value.reduce((n,r)=>n+Number(r.quote?.amount||0),0))
const metric=ref('money')
const unit=computed(()=>metric.value==='money'?'元':metric.value==='WEIGHT'?'公斤':'筐')
const metricName=computed(()=>metric.value==='money'?'工资':metric.value==='WEIGHT'?'采摘重量':'采摘筐数')
const chartA=ref<HTMLElement>(),chartB=ref<HTMLElement>(),chartC=ref<HTMLElement>()
let charts:echarts.ECharts[]=[]
function render(){
  if(!chartA.value||!chartB.value||!chartC.value)return
  if(!charts.length)charts=[echarts.init(chartA.value),echarts.init(chartB.value),echarts.init(chartC.value)]
  const byProduct:Record<string,number>={},byWorker:Record<string,number>={},days:Record<string,number>={}
  for(const r of valid.value){const value=metric.value==='money'?Number(r.quote?.amount||0):(r.quote?.parts||[]).filter(p=>p.type===metric.value).reduce((sum,p)=>sum+Number(p.quantity),0);if(value===0)continue;byProduct[r.product_name]=(byProduct[r.product_name]||0)+value;byWorker[r.worker_name]=(byWorker[r.worker_name]||0)+value;days[r.work_date]=(days[r.work_date]||0)+value}
  const names=Object.keys(byProduct).sort((a,b)=>byProduct[a]-byProduct[b]);const color='#39715B'
  charts[0].setOption({textStyle:{fontFamily:'Microsoft YaHei, sans-serif',color:'#6d7b72'},tooltip:{trigger:'axis'},grid:{left:65,right:30,top:15,bottom:25},xAxis:{type:'value',name:unit.value,splitLine:{lineStyle:{color:'#edf0e9'}}},yAxis:{type:'category',data:names,axisLine:{show:false},axisTick:{show:false}},series:[{type:'bar',data:names.map(n=>byProduct[n]),barWidth:20,itemStyle:{color,borderRadius:[0,4,4,0]}}]})
  charts[1].setOption({color:['#245447','#75a17f','#c5d7a6','#cb9a42','#9eaa9c','#527b73'],tooltip:{trigger:'item',formatter:'{b}: {c} '+unit.value+' ({d}%)'},legend:{bottom:0,type:'scroll'},series:[{type:'pie',radius:['45%','70%'],center:['50%','43%'],data:Object.entries(byWorker).map(([name,value])=>({name,value})),label:{show:false},itemStyle:{borderWidth:3,borderColor:'#fff'}}]})
  const dates=Object.keys(days).sort();charts[2].setOption({tooltip:{trigger:'axis'},grid:{left:55,right:25,top:25,bottom:30},xAxis:{type:'category',data:dates.map(d=>d.slice(5)),boundaryGap:false,axisLine:{lineStyle:{color:'#cbd5ca'}}},yAxis:{type:'value',name:metricName.value+' / '+unit.value,splitLine:{lineStyle:{color:'#edf0e9'}}},series:[{type:'line',smooth:true,data:dates.map(d=>days[d]),symbolSize:6,lineStyle:{color,width:3},itemStyle:{color},areaStyle:{color:'#e5eee2'}}]})
}
const resize=()=>charts.forEach(c=>c.resize())
onMounted(render);watch(valid,async()=>{await nextTick();render()},{deep:true});window.addEventListener('resize',resize);onBeforeUnmount(()=>{window.removeEventListener('resize',resize);charts.forEach(c=>c.dispose())})
watch(metric,render)
</script>
<template><div class="page-intro"><div><p class="eyebrow">每一份收获，都有据可查</p><h1>农场概览<span class="title-dot">.</span></h1><p class="muted">{{month}} · {{boot?.user.role==='ADMIN'?'农场全量账本':'你可查看的采摘记录'}}</p></div><button class="primary" @click="emit('create')"><NotebookPen :size="18"/>记一笔采摘</button></div>
  <div class="field-banner"><div class="field-lines" aria-hidden="true"></div><div><span class="tag light">田间账本 / 本地演示</span><h2>把田间的辛劳，<br/>记成清楚的账。</h2><p>按日期与时段记录，联网后汇集，月底安心核对。</p></div><button @click="emit('navigate','records')">查看采摘账本 <ArrowUpRight :size="20"/></button><Sprout class="banner-sprout" :size="150" :stroke-width="0.8"/></div>
  <div class="stats-grid"><article class="stat"><Coins/><span>本月核算工资</span><strong>¥ {{amount.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}}</strong><small>根据当前已接收记录核算</small></article><article class="stat"><NotebookPen/><span>采摘记录</span><strong>{{valid.length}}<small>笔</small></strong><small>按工人、日期、时段、产品归档</small></article><article class="stat"><Users/><span>本月参与工人</span><strong>{{new Set(valid.map(r=>r.worker_id)).size}}<small>人</small></strong><small>多名记录员协作，按工人汇总</small></article><article class="stat"><Sprout/><span>在用产品</span><strong>{{boot?.products.filter(p=>p.active).length||0}}<small>种</small></strong><small>计费规则分别配置在具体产品</small></article></div>
  <div class="ledger-toolbar"><span class="muted">统计口径 · 只比较同一单位的工作量</span><div class="segmented"><button :class="{selected:metric==='money'}" @click="metric='money'">工资金额</button><button :class="{selected:metric==='WEIGHT'}" @click="metric='WEIGHT'">重量 / 公斤</button><button :class="{selected:metric==='PIECE'}" @click="metric='PIECE'">计件 / 筐</button></div></div><div class="charts-grid"><section class="panel"><div class="section-title"><h3>产品{{metricName}}排行</h3><span class="muted">单位：{{unit}}</span></div><div ref="chartA" class="chart" role="img" aria-label="各产品工资金额柱状图"/></section><section class="panel"><div class="section-title"><h3>工人{{metricName}}占比</h3><span class="muted">当前可见记录</span></div><div ref="chartB" class="chart" role="img" aria-label="工人工资金额占比饼图"/></section><section class="panel wide"><div class="section-title"><h3>每日{{metricName}}趋势</h3><span class="muted">按实际采摘日期</span></div><div ref="chartC" class="chart" role="img" aria-label="每日采摘工资折线图"/></section></div>
</template>
