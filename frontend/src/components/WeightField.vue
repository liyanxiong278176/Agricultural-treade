<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {boot} from '../data'
import type {Weighing} from '../types'
const props=defineProps<{label:string;unit:string;modelValue:string|null|undefined;capture?:Weighing}>()
const emit=defineEmits<{'update:modelValue':[value:string];'update:capture':[value:Weighing|undefined]}>()
const grossMode=ref(!!props.capture),grossId=ref(props.capture?.grossId||''),basketId=ref(props.capture?.basketId||''),count=ref(props.capture?.count||1)
const factor=computed(()=>props.unit==='jin'?2:props.unit==='g'?1000:1)
const unitName=computed(()=>props.unit==='jin'?'斤':props.unit==='g'?'克':'公斤')
const grossOption=computed(()=>boot.value?.grossWeights?.find(w=>w.id===grossId.value))
const basket=computed(()=>boot.value?.baskets?.find(w=>w.id===basketId.value))
const inUnit=(kg:any)=>Number((Number(kg||0)*factor.value).toFixed(6))
const gross=computed(()=>inUnit(grossOption.value?.weight_kg))
const basketWeight=computed(()=>inUnit(basket.value?.weight_kg))
const deduction=computed(()=>basketWeight.value*count.value)
const ready=computed(()=>!!grossOption.value&&!!basket.value&&Number.isInteger(count.value)&&count.value>=1)
const net=computed(()=>Math.round((gross.value-deduction.value)*1e6)/1e6)
function update(){
  if(!grossMode.value){emit('update:capture',undefined);return}
  if(!ready.value)return
  emit('update:capture',{gross:String(gross.value),tare:String(deduction.value),grossId:grossId.value,grossRevision:grossOption.value!.revision||1,basketId:basketId.value,basketRevision:basket.value!.revision||1,basketWeight:String(basketWeight.value),count:Number(count.value)})
  emit('update:modelValue',String(net.value))
}
watch([grossMode,grossId,basketId,count,()=>props.unit,grossOption,basket],update,{immediate:true})
</script>
<template><div class="weight-field">
<label class="check-label"><input v-model="grossMode" type="checkbox"/>{{label}}：按毛重扣筐重</label>
<div v-if="grossMode" class="quantity-panel tare-card">
  <div class="form-grid"><label>毛重配置<select v-model="grossId" aria-label="毛重配置" required><option value="" disabled>选择毛重</option><option v-for="w in boot?.grossWeights?.filter(w=>w.active)" :key="w.id" :value="w.id">{{w.name}} · {{inUnit(w.weight_kg)}} {{unitName}}</option></select></label>
  <label>筐型<select v-model="basketId" aria-label="筐型" required><option value="" disabled>选择筐型与单筐重</option><option v-for="b in boot?.baskets?.filter(b=>b.active)" :key="b.id" :value="b.id">{{b.name}} · {{inUnit(b.weight_kg)}} {{unitName}}/个</option></select></label>
  <label>实际容器数量<input v-model.number="count" aria-label="实际容器数量" type="number" min="1" max="100000" step="1" required/></label></div>
  <p v-if="!boot?.grossWeights?.some(w=>w.active)||!boot?.baskets?.some(w=>w.active)" class="notice">请先到“重量配置”维护毛重与筐重。老板和记录员均可维护。</p>
  <p v-if="ready" :class="net<0?'notice danger':'notice'">毛重 {{gross}} {{unitName}} · 扣筐重 {{deduction}} {{unitName}} · 净重 {{net}} {{unitName}}</p>
  <small class="muted">配置分别选择；容器数量只用于扣重，不作为计薪筐数。</small>
</div>
<label :class="{'weight-result':grossMode}">{{grossMode?'实际重量（'+label+'）':label}}
<input :aria-label="grossMode?'实际重量':label" :value="grossMode?(ready?net:''):modelValue" :readonly="grossMode" @input="!grossMode&&emit('update:modelValue',($event.target as HTMLInputElement).value)" type="number" min="0" step="any" inputmode="decimal" required :placeholder="grossMode?'选择毛重、筐重后自动计算':'已扣筐的净重'"/>
</label>
</div></template>
