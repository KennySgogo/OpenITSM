<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import ErrorNotice from '../components/ErrorNotice.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { ticketStatusLabels, type TicketStatus } from '../types/ApiTypes';
interface Metrics { asOf: string; totalCount: number; countsByStatus: Record<TicketStatus, number>;
  countsByBusinessType: { id: string; name: string; count: number }[]; unassignedCount: number;
  assigneeLoads: { id: string; name: string; openCount: number }[]; averageResolutionSeconds: number | null; resolutionSampleCount: number }
const filters = reactive({ createdFrom: '', createdTo: '' });
const metrics = ref<Metrics>();
const { error, busy, blocked, showError } = useFeedback();
async function load() {
  busy.value = true; error.value = undefined; metrics.value = undefined;
  const params = new URLSearchParams();
  if (filters.createdFrom) params.set('createdFrom', filters.createdFrom);
  if (filters.createdTo) params.set('createdTo', filters.createdTo);
  try { metrics.value = await request<Metrics>(`/dashboard/metrics?${params}`); }
  catch (value) { showError(value); } finally { busy.value = false; }
}
onMounted(load);
</script>
<template>
  <div class="page-heading"><div><h1>服务看板</h1><p>按创建日期统计；解决耗时包含等待，仅统计当前已解决或关闭的有效样本。</p></div></div>
  <form @submit.prevent="load"><ElDatePicker v-model="filters.createdFrom" type="date" value-format="YYYY-MM-DD" placeholder="创建起日" /><ElDatePicker v-model="filters.createdTo" type="date" value-format="YYYY-MM-DD" placeholder="创建止日" /><ElButton native-type="submit" type="primary" :disabled="blocked">查询 / 刷新</ElButton></form>
  <ErrorNotice :error="error" />
  <div v-if="metrics" v-loading="busy">
    <p>统计时点 {{ new Date(metrics.asOf).toLocaleString('zh-CN') }}</p>
    <ElRow :gutter="20"><ElCol :span="6"><ElStatistic title="工单总数" :value="metrics.totalCount" /></ElCol><ElCol :span="6"><ElStatistic title="未分派未结束" :value="metrics.unassignedCount" /></ElCol><ElCol :span="12"><p>平均解决耗时：{{ metrics.averageResolutionSeconds === null ? '暂无数据' : (metrics.averageResolutionSeconds / 3600).toFixed(2) + ' 小时' }}（{{ metrics.resolutionSampleCount }} 个有效样本）</p></ElCol></ElRow>
    <h2>状态分布</h2><ElTable :data="Object.entries(metrics.countsByStatus).map(([status, count]) => ({ status, count }))"><ElTableColumn label="状态"><template #default="{ row }">{{ ticketStatusLabels[row.status as TicketStatus] }}</template></ElTableColumn><ElTableColumn prop="count" label="数量" /></ElTable>
    <h2>业务类型</h2><ElTable :data="metrics.countsByBusinessType"><ElTableColumn prop="name" label="类型" /><ElTableColumn prop="count" label="数量" /></ElTable>
    <h2>人员未结束负载</h2><ElTable :data="metrics.assigneeLoads"><ElTableColumn prop="name" label="负责人" /><ElTableColumn prop="openCount" label="未结束数量" /></ElTable>
  </div>
</template>
