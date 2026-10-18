<script setup lang="ts">
import {ref} from 'vue'
import {boot} from '../data'
import DataList from './DataList.vue'
defineProps<{month:string}>()
const tick=ref(0),plotId=ref('*'),productId=ref(''),search=ref('')
</script>
<template><div class="page-intro"><div><p class="eyebrow">数量有归属，成本可对照</p><h1>地块人工成本</h1><p class="muted">{{month}} · 按当前核算工资分摊。</p></div><button @click="tick++">刷新成本</button></div><p class="notice">各棚成本合计等于对应采摘工资。公斤与筐分别统计，未指定地块单独列示。</p>
<div class="list-query"><label>查询关键字<input v-model="search" aria-label="成本查询" placeholder="地块或产品名称"/></label><label>地块<select v-model="plotId"><option value="*">全部地块</option><option value="">未指定地块</option><option v-for="p in boot?.plots" :key="p.id" :value="p.id">{{p.name}}</option></select></label><label>产品<select v-model="productId"><option value="">全部产品</option><option v-for="p in boot?.products" :key="p.id" :value="p.id">{{p.name}}</option></select></label><button @click="tick++">查询</button><button @click="plotId='*';productId='';search=''">重置</button></div>
<DataList endpoint="/query/plot-costs" :params="{month,plotId,productId,q:search}" :refresh-key="tick" :show-search="false" scope="地块人工成本" v-slot="{items}"><section class="panel table-wrap"><table><thead><tr><th>地块 / 大棚</th><th>产品</th><th>计重 / 公斤</th><th>计件 / 筐</th><th>采摘人工成本 / 元</th><th>单位采摘人工成本</th></tr></thead><tbody><tr v-for="(s,i) in items" :key="i"><td>{{s.plot}}</td><td>{{s.product}}</td><td>{{Number(Number(s.kg).toFixed(6))}}</td><td>{{s.pieces}}</td><td>{{Number(s.amount).toFixed(2)}}</td><td>{{s.weightOnly&&s.kg?(Number(s.amount)/Number(s.kg)).toFixed(4)+' 元/公斤':s.pieceOnly&&s.pieces?(Number(s.amount)/Number(s.pieces)).toFixed(4)+' 元/筐':'—（数量分别统计）'}}</td></tr></tbody></table></section></DataList></template>
