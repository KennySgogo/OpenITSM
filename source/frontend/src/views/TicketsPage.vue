<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ErrorNotice from '../components/ErrorNotice.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { ticketStatusLabels, ticketViewLabels } from '../types/ApiTypes';
import type { TicketPage, TicketView } from '../types/ApiTypes';

const route = useRoute();
const router = useRouter();
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const filters = reactive({ q: '', status: '', businessType: '' });
const result = ref<TicketPage>({ items: [], page: 1, pageSize: 20, total: 0 });
const view = computed(() => String(route.query.view ?? 'MINE'));
const title = computed(() => ticketViewLabels[view.value as TicketView] || '工单');
let sequence = 0;
onUnmounted(() => { sequence++; });

async function load() {
  const current = ++sequence;
  busy.value = true;
  error.value = undefined;
  result.value = { items: [], page: 1, pageSize: 20, total: 0 };
  const params = new URLSearchParams({ view: view.value });
  for (const key of ['page', 'q', 'status', 'businessType']) {
    const value = route.query[key];
    if (value !== undefined) params.set(key, String(value ?? ''));
  }
  try {
    const page = await request<TicketPage>(`/tickets?${params}`);
    if (current === sequence) result.value = page;
  } catch (value) { if (current === sequence) showError(value); }
  finally { if (current === sequence) busy.value = false; }
}

watch(() => route.fullPath, () => {
  filters.q = String(route.query.q ?? '');
  filters.status = String(route.query.status ?? '');
  filters.businessType = String(route.query.businessType ?? '');
  void load();
}, { immediate: true });

function search() {
  const query: Record<string, string> = { view: view.value };
  if (filters.q.trim()) query.q = filters.q.trim();
  if (filters.status) query.status = filters.status;
  if (filters.businessType.trim()) query.businessType = filters.businessType.trim();
  if (router.resolve({ path: '/tickets', query }).fullPath === route.fullPath) void load();
  else void router.push({ path: '/tickets', query });
}
function reset() { Object.assign(filters, { q: '', status: '', businessType: '' }); search(); }
function changePage(page: number) { void router.push({ path: '/tickets', query: { ...route.query, page: String(page) } }); }
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">服务工单</span><h1>{{ title }}</h1><p>查看工单状态、负责人和问题详情。</p></div><RouterLink to="/tickets/new"><ElButton type="primary">提交工单</ElButton></RouterLink></div>
  <section class="tickets-card">
    <form class="ticket-filters" @submit.prevent="search">
      <ElInput v-model="filters.q" placeholder="搜索编号、标题或描述" aria-label="关键词" clearable />
      <ElSelect v-model="filters.status" placeholder="全部状态" aria-label="工单状态" clearable><ElOption v-for="(label, status) in ticketStatusLabels" :key="status" :label="label" :value="status" /></ElSelect>
      <ElInput v-model="filters.businessType" placeholder="业务类型（精确匹配）" aria-label="业务类型" clearable />
      <ElButton type="primary" native-type="submit" :disabled="blocked">搜索</ElButton><ElButton :disabled="blocked" @click="reset">重置</ElButton>
    </form>
    <ErrorNotice :error="error" />
    <div v-if="error" class="ticket-retry"><ElButton :disabled="blocked" @click="load">{{ waitSeconds ? `${waitSeconds} 秒后重试` : '重新加载' }}</ElButton></div>
    <ElTable v-loading="busy" :data="result.items" row-key="id" class="member-table" :empty-text="error ? '工单加载失败' : '暂无符合条件的工单'">
      <ElTableColumn label="工单编号" min-width="125"><template #default="{ row }"><RouterLink :to="`/tickets/${row.id}`">{{ row.number }}</RouterLink></template></ElTableColumn>
      <ElTableColumn prop="title" label="标题" min-width="230" show-overflow-tooltip />
      <ElTableColumn label="状态" width="105"><template #default="{ row }">{{ ticketStatusLabels[row.status as keyof typeof ticketStatusLabels] }}</template></ElTableColumn>
      <ElTableColumn prop="priority" label="优先级" width="85" />
      <ElTableColumn prop="businessType" label="业务类型" min-width="120" show-overflow-tooltip />
      <ElTableColumn prop="requesterName" label="提单人" min-width="100" />
      <ElTableColumn label="负责人" min-width="100"><template #default="{ row }">{{ row.assigneeName ?? '未分派' }}</template></ElTableColumn>
      <ElTableColumn label="创建时间" min-width="165"><template #default="{ row }">{{ new Date(row.createdAt).toLocaleString('zh-CN') }}</template></ElTableColumn>
    </ElTable>
    <div class="table-footer"><span>共 {{ result.total }} 张工单</span><ElPagination :current-page="result.page" :page-size="20" :total="result.total" layout="prev, pager, next" background :disabled="blocked || !!error" @current-change="changePage" /></div>
  </section>
</template>

<style scoped>
.tickets-card { background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; padding: 0 22px; }
.ticket-filters { display: flex; gap: 10px; padding: 22px 0; }
.ticket-filters > .el-input { max-width: 260px; }
.ticket-filters > .el-select { width: 155px; }
.ticket-filters .el-button + .el-button { margin-left: 0; }
.ticket-retry { padding: 12px 0; }
</style>
