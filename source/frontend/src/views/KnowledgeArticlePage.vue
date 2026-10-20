<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';
import KnowledgeAttachments from '../components/KnowledgeAttachments.vue';
import ErrorNotice from '../components/ErrorNotice.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { sessionState } from '../stores/SessionStore';
import type { KnowledgeArticle, SaveKnowledgeArticle, MemberAction } from '../types/ApiTypes';

const route = useRoute(); const router = useRouter();
const user = computed(() => sessionState.session?.user);
const isNew = computed(() => route.path === '/knowledge/new');
const attachmentLocked = ref(false);
const article = ref<KnowledgeArticle>(); const editing = ref(false); const loading = ref(false);
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const form = ref<FormInstance>();
const model = reactive<SaveKnowledgeArticle>({ title: '', content: '', category: '' });
const pending = ref<{ path: string; method: string; body?: SaveKnowledgeArticle; version?: number; key: string }>();
const canEdit = computed(() => user.value?.role === 'ENGINEER' && article.value?.authorId === user.value.id && article.value?.status === 'DRAFT');
const listPath = computed(() => user.value?.role === 'ADMIN' ? '/knowledge?view=ALL' : user.value?.role === 'ENGINEER' ? '/knowledge?view=MINE' : '/knowledge');
const rules: FormRules = Object.fromEntries([['title', '请填写标题'], ['category', '请填写分类'], ['content', '请填写正文']].map(([field, message]) => [field, [{ required: true, whitespace: true, message, trigger: 'blur' }]]));
let sequence = 0;
onUnmounted(() => { sequence++; });
async function load(keepDraft = false) {
  const current = ++sequence;
  loading.value = true;
  if (!keepDraft) { article.value = undefined; error.value = undefined; editing.value = false; }
  if (isNew.value) {
    Object.assign(model, { title: '', content: '', category: '' }); editing.value = true; loading.value = false; return;
  }
  try {
    const value = await request<KnowledgeArticle>(`/knowledge-articles/${encodeURIComponent(String(route.params.id))}`);
    if (current === sequence) article.value = value;
  } catch (value) { if (current === sequence) { article.value = undefined; showError(value); } }
  finally { if (current === sequence) loading.value = false; }
}
watch(() => route.path, () => { pending.value = undefined; void load(); }, { immediate: true });
function startEdit() {
  if (!article.value || !canEdit.value || blocked.value || loading.value || attachmentLocked.value) return;
  Object.assign(model, { title: article.value.title, content: article.value.content, category: article.value.category });
  editing.value = true; error.value = undefined;
}
async function save() {
  if (blocked.value || loading.value || attachmentLocked.value || (!isNew.value && !canEdit.value)) return;
  if (!pending.value) {
    if (!await form.value?.validate().catch(() => false)) return;
    pending.value = { path: isNew.value ? '/knowledge-articles' : `/knowledge-articles/${article.value!.id}`,
      method: isNew.value ? 'POST' : 'PATCH', body: { ...model }, version: isNew.value ? undefined : article.value!.version, key: crypto.randomUUID() };
  }
  await submit();
}
async function transition(action: 'publish' | 'unpublish') {
  if (!article.value || user.value?.role !== 'ADMIN' || blocked.value || loading.value || attachmentLocked.value || pending.value) return;
  pending.value = { path: `/knowledge-articles/${article.value.id}/${action}`, method: 'POST', version: article.value.version, key: crypto.randomUUID() };
  await submit();
}
async function submit() {
  if (!pending.value || blocked.value || loading.value || attachmentLocked.value) return;
  busy.value = true; error.value = undefined;
  try {
    const snapshot = pending.value;
    const result = await request<MemberAction>(snapshot.path, snapshot);
    pending.value = undefined; editing.value = false;
    if (isNew.value) await router.replace(`/knowledge/${result.resourceId}`);
    else await load();
  } catch (value) {
    showError(value);
    const failure = error.value!;
    if (failure.status >= 400 && failure.status < 500 && !['REQUEST_IN_PROGRESS', 'RATE_LIMITED'].includes(failure.code)) {
      pending.value = undefined;
      if (['VERSION_CONFLICT', 'INVALID_STATE'].includes(failure.code)) await load(true);
    }
  } finally { busy.value = false; }
}
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">团队知识</span><h1>{{ isNew ? '新建知识草稿' : '知识详情' }}</h1></div><RouterLink :to="listPath"><ElButton>返回知识列表</ElButton></RouterLink></div>
  <ErrorNotice :error="error" />
  <ElAlert v-if="pending && error" class="article-notice" title="操作结果尚未确认。请重试原操作，内容、版本和幂等键会保持不变。" type="info" :closable="false" />
  <ElButton v-if="pending && error" :disabled="attachmentLocked || blocked || loading" @click="submit">{{ waitSeconds ? `${waitSeconds} 秒后重试` : '重试原操作' }}</ElButton>
  <ElButton v-else-if="error && !isNew && !article" :disabled="attachmentLocked || blocked || loading" @click="load()">重新加载</ElButton>
  <section v-if="isNew || article || loading" v-loading="loading" class="article-card">
    <template v-if="article">
      <div class="article-heading"><h2>{{ article.title }}</h2><ElTag :type="article.status === 'PUBLISHED' ? 'success' : 'info'">{{ article.status === 'PUBLISHED' ? '已发布' : '草稿' }}</ElTag></div>
      <p class="article-meta">{{ article.category }} · 作者 {{ article.authorName }} · 更新于 {{ new Date(article.updatedAt).toLocaleString('zh-CN') }}<span v-if="article.publishedAt"> · 发布于 {{ new Date(article.publishedAt).toLocaleString('zh-CN') }}</span></p>
      <div v-if="!editing" class="article-actions"><ElButton :disabled="attachmentLocked || blocked || loading || !!pending" @click="load()">刷新知识</ElButton><ElButton v-if="canEdit" type="primary" :disabled="attachmentLocked || blocked || loading || !!pending" @click="startEdit">编辑草稿</ElButton><template v-if="user?.role === 'ADMIN'"><ElButton v-if="article.status === 'DRAFT'" type="primary" :disabled="attachmentLocked || blocked || loading || !!pending" @click="transition('publish')">发布知识</ElButton><ElButton v-else type="warning" :disabled="attachmentLocked || blocked || loading || !!pending" @click="transition('unpublish')">下架知识</ElButton></template></div>
      <p v-if="!editing" class="article-content">{{ article.content }}</p>
    </template>
    <template v-if="editing">
      <ElAlert v-if="!isNew && !canEdit" class="article-notice" title="知识状态或权限已改变，当前内容无法继续保存。请核对最新知识。" type="warning" :closable="false" />
      <ElForm ref="form" :model="model" :rules="rules" label-position="top" :disabled="busy || loading || !!pending || (!isNew && !canEdit)" @submit.prevent="save">
        <ElFormItem label="标题" prop="title"><ElInput v-model="model.title" placeholder="填写知识标题" /></ElFormItem><ElFormItem label="分类" prop="category"><ElInput v-model="model.category" placeholder="填写分类" /></ElFormItem><ElFormItem label="正文" prop="content"><ElInput v-model="model.content" type="textarea" :rows="12" placeholder="填写问题现象、排查方法与解决步骤，仅保存纯文本" /></ElFormItem>
      </ElForm>
      <p class="article-meta">草稿仅作者工程师和管理员可见，由管理员发布后供团队阅读。</p>
      <div class="article-actions"><ElButton type="primary" :loading="busy" :disabled="attachmentLocked || blocked || loading || !!pending || (!isNew && !canEdit)" @click="save">{{ isNew ? '保存草稿' : '保存修改' }}</ElButton><ElButton v-if="!isNew" :disabled="blocked || !!pending" @click="editing = false; error = undefined">取消编辑</ElButton></div>
    </template>
  </section>
  <KnowledgeAttachments v-if="article" :key="article.id" :article="article" :refreshing="loading" :external-blocked="busy || !!pending || editing" @lock="attachmentLocked = $event" @refresh="load(true)" />
</template>

<style scoped>
.article-card { padding: 28px; background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; min-height: 200px; }
.article-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.article-heading h2 { margin: 0; font-size: 23px; overflow-wrap: anywhere; }
.article-meta { color: #64748b; font-size: 13px; overflow-wrap: anywhere; line-height: 1.8; }
.article-content { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; margin-top: 24px; }
.article-actions { display: flex; gap: 8px; margin: 20px 0; }
.article-actions .el-button + .el-button { margin-left: 0; }
.article-notice { margin-bottom: 16px; }
</style>
