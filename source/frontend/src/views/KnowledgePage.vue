<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ErrorNotice from '../components/ErrorNotice.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { sessionState } from '../stores/SessionStore';
import type { KnowledgeArticlePage } from '../types/ApiTypes';

const route = useRoute();
const router = useRouter();
const role = computed(() => sessionState.session?.user.role);
const view = computed(() => String(route.query.view ?? 'PUBLISHED'));
const title = computed(() => ({ PUBLISHED: '已发布知识', MINE: '我的知识', ALL: '知识管理' }[view.value] ?? '知识库'));
const filters = reactive({ q: '', category: '' });
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const result = ref<KnowledgeArticlePage>({ items: [], page: 1, pageSize: 20, total: 0 });
let sequence = 0;
onUnmounted(() => { sequence++; });
async function load() {
  const current = ++sequence;
  busy.value = true; error.value = undefined;
  result.value = { items: [], page: 1, pageSize: 20, total: 0 };
  const params = new URLSearchParams({ view: view.value });
  for (const field of ['q', 'category', 'page']) if (route.query[field] !== undefined) params.set(field, String(route.query[field] ?? ''));
  try { const page = await request<KnowledgeArticlePage>(`/knowledge-articles?${params}`); if (current === sequence) result.value = page; }
  catch (value) { if (current === sequence) showError(value); }
  finally { if (current === sequence) busy.value = false; }
}
watch(() => route.fullPath, () => {
  filters.q = String(route.query.q ?? ''); filters.category = String(route.query.category ?? ''); void load();
}, { immediate: true });
function search() {
  const query: Record<string, string> = { view: view.value };
  if (filters.q.trim()) query.q = filters.q.trim();
  if (filters.category.trim()) query.category = filters.category.trim();
  if (router.resolve({ path: '/knowledge', query }).fullPath === route.fullPath) void load();
  else void router.push({ path: '/knowledge', query });
}
function page(number: number) { void router.push({ path: '/knowledge', query: { ...route.query, page: String(number) } }); }
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">团队知识</span><h1>{{ title }}</h1><p>查找已发布的处理方法和解决经验。</p></div><RouterLink v-if="role === 'ENGINEER'" to="/knowledge/new"><ElButton type="primary">新建知识草稿</ElButton></RouterLink></div>
  <div class="knowledge-views"><RouterLink to="/knowledge"><ElButton :type="view === 'PUBLISHED' ? 'primary' : 'default'">已发布知识</ElButton></RouterLink><RouterLink v-if="role === 'ENGINEER'" to="/knowledge?view=MINE"><ElButton :type="view === 'MINE' ? 'primary' : 'default'">我的知识</ElButton></RouterLink><RouterLink v-if="role === 'ADMIN'" to="/knowledge?view=ALL"><ElButton :type="view === 'ALL' ? 'primary' : 'default'">知识管理</ElButton></RouterLink></div>
  <section class="knowledge-card">
    <form class="knowledge-filters" @submit.prevent="search"><ElInput v-model="filters.q" aria-label="知识关键词" placeholder="搜索标题或正文" clearable /><ElInput v-model="filters.category" aria-label="知识分类" placeholder="分类（精确匹配）" clearable /><ElButton type="primary" native-type="submit" :disabled="blocked">搜索</ElButton><ElButton :disabled="blocked" @click="filters.q = ''; filters.category = ''; search()">重置</ElButton></form>
    <ErrorNotice :error="error" /><ElButton v-if="error" :disabled="blocked" @click="load">{{ waitSeconds ? `${waitSeconds} 秒后重试` : '重新加载' }}</ElButton>
    <ElTable v-loading="busy" :data="result.items" row-key="id" :empty-text="error ? '知识加载失败' : '暂无符合条件的知识'">
      <ElTableColumn label="标题" min-width="280"><template #default="{ row }"><RouterLink :to="`/knowledge/${row.id}`">{{ row.title }}</RouterLink></template></ElTableColumn>
      <ElTableColumn prop="category" label="分类" min-width="120" show-overflow-tooltip />
      <ElTableColumn label="状态" width="100"><template #default="{ row }"><ElTag :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{ row.status === 'PUBLISHED' ? '已发布' : '草稿' }}</ElTag></template></ElTableColumn>
      <ElTableColumn prop="authorName" label="作者" min-width="110" />
      <ElTableColumn label="更新时间" min-width="180"><template #default="{ row }">{{ new Date(row.updatedAt).toLocaleString('zh-CN') }}</template></ElTableColumn>
    </ElTable>
    <div class="table-footer"><span>共 {{ result.total }} 篇知识</span><ElPagination :current-page="result.page" :page-size="20" :total="result.total" layout="prev, pager, next" background :disabled="blocked || !!error" @current-change="page" /></div>
  </section>
</template>

<style scoped>
.knowledge-views { display: flex; gap: 10px; margin-bottom: 18px; }
.knowledge-card { padding: 0 22px; background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; }
.knowledge-filters { display: flex; gap: 10px; padding: 22px 0; }
.knowledge-filters > .el-input { max-width: 300px; }
</style>
