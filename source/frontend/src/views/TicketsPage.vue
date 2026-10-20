<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ErrorNotice from '../components/ErrorNotice.vue';
import { sessionState } from '../stores/SessionStore';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { ticketStatusLabels, ticketViewLabels } from '../types/ApiTypes';
import type { TicketPage, TicketView } from '../types/ApiTypes';

const route = useRoute();
const router = useRouter();
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const emptyFilters = { q: '', status: '', businessTypeId: '', lifecycle: '', createdFrom: '', createdTo: '', priority: '', requesterId: '', assigneeId: '' };
const filters = reactive({ ...emptyFilters });
const staff = computed(() => sessionState.session?.user.role !== 'EMPLOYEE');
const options = ref<{ businessTypes: { id: string; name: string; active: boolean }[]; requesters?: { id: string; name: string }[]; assignees?: { id: string; name: string }[] }>({ businessTypes: [] });
const states = computed(() => Object.entries(ticketStatusLabels).filter(([status]) => !filters.lifecycle || filters.lifecycle === 'ALL' || (filters.lifecycle === 'ENDED' ? ['CLOSED', 'REJECTED'].includes(status) : !['CLOSED', 'REJECTED'].includes(status))));
onMounted(async () => { try { options.value = await request('/tickets/filter-options'); } catch (value) { showError(value); } });
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
  for (const key of ['page', ...Object.keys(emptyFilters)]) {
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
  for (const key of Object.keys(emptyFilters) as (keyof typeof emptyFilters)[]) filters[key] = String(route.query[key] ?? (key === 'lifecycle' ? (view.value === 'ASSIGNED' ? 'OPEN' : 'ALL') : ''));
  void load();
}, { immediate: true });

function search() {
  const query: Record<string, string> = { view: view.value };
  for (const key of Object.keys(emptyFilters) as (keyof typeof emptyFilters)[]) if (filters[key]) query[key] = filters[key];
  if (router.resolve({ path: '/tickets', query }).fullPath === route.fullPath) void load();
  else void router.push({ path: '/tickets', query });
}
function reset() { Object.assign(filters, { ...emptyFilters }); search(); }
function changePage(page: number) { void router.push({ path: '/tickets', query: { ...route.query, page: String(page) } }); }
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">服务工单</span><h1>{{ title }}</h1><p>查看工单状态、负责人和问题详情。</p></div><RouterLink to="/tickets/new"><ElButton type="primary">提交工单</ElButton></RouterLink></div>
  <section class="tickets-card">
    <form class="ticket-filters" @submit.prevent="search">
      <ElInput v-model="filters.q" placeholder="搜索编号、标题或描述" aria-label="关键词" clearable />
      <ElSelect v-model="filters.status" placeholder="全部状态" aria-label="工单状态" clearable><ElOption v-for="[status, label] in states" :key="status" :label="label" :value="status" /></ElSelect>
      <ElSelect v-model="filters.businessTypeId" placeholder="全部业务类型" clearable><ElOption v-for="type in options.businessTypes" :key="type.id" :value="type.id" :label="type.name + (type.active ? '' : '（已移除）')" /></ElSelect>
      <ElSelect v-model="filters.lifecycle" placeholder="结束范围" @change="filters.status = ''"><ElOption label="未结束" value="OPEN" /><ElOption label="已结束" value="ENDED" /><ElOption label="全部" value="ALL" /></ElSelect>
      <ElDatePicker v-model="filters.createdFrom" type="date" value-format="YYYY-MM-DD" placeholder="创建起日" /><ElDatePicker v-model="filters.createdTo" type="date" value-format="YYYY-MM-DD" placeholder="创建止日" />
      <ElSelect v-model="filters.priority" clearable placeholder="全部优先级"><ElOption v-for="value in ['P1','P2','P3','P4']" :key="value" :label="value" :value="value" /></ElSelect>
      <ElSelect v-if="staff" v-model="filters.requesterId" clearable filterable placeholder="全部提单人"><ElOption v-for="person in options.requesters" :key="person.id" :value="person.id" :label="person.name" /></ElSelect>
      <ElSelect v-if="staff" v-model="filters.assigneeId" clearable filterable placeholder="全部负责人"><ElOption v-for="person in options.assignees" :key="person.id" :value="person.id" :label="person.name" /></ElSelect>
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
.ticket-filters { display: flex; flex-wrap: wrap; gap: 10px; padding: 22px 0; }
.ticket-filters > .el-input { max-width: 260px; }
.ticket-filters > .el-select { width: 155px; }
.ticket-filters .el-button + .el-button { margin-left: 0; }
.ticket-retry { padding: 12px 0; }
</style>
