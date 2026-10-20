<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import ErrorNotice from './ErrorNotice.vue';
import { ApiError, request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { sessionState } from '../stores/SessionStore';
import type { KnowledgeArticle, MemberAction } from '../types/ApiTypes';
interface KnowledgeAttachment { id: string; fileName: string; size: number; uploaderId: string; uploaderName: string; createdAt: string }
interface KnowledgeAttachmentPage { items: KnowledgeAttachment[]; page: number; pageSize: number; total: number }

const props = defineProps<{ article: KnowledgeArticle; refreshing: boolean; externalBlocked: boolean }>();
const emit = defineEmits<{ refresh: []; lock: [value: boolean] }>();
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const { error: listError, busy: listBusy, blocked: listBlocked, showError: showListError } = useFeedback();
const { error: downloadError, busy: downloadBusy, showError: showDownloadError } = useFeedback();
const attachments = ref<KnowledgeAttachmentPage>();
const page = ref(1);
const selected = ref<File>();
const input = ref<HTMLInputElement>();
const permitted = computed(() => sessionState.session?.user.role === 'ENGINEER' && props.article.authorId === sessionState.session.user.id && props.article.status === 'DRAFT');
const pending = ref<{ body?: FormData; version: number; key: string; path: string; method: string }>();
let sequence = 0;
let alive = true;
let followLatest = false;
watch(() => busy.value || !!pending.value, value => emit('lock', value), { immediate: true });
onUnmounted(() => { alive = false; sequence++; emit('lock', false); });

async function load(nextPage = page.value, latest = false) {
  const current = ++sequence;
  page.value = nextPage;
  attachments.value = undefined;
  listError.value = undefined;
  listBusy.value = true;
  try {
    let result = await request<KnowledgeAttachmentPage>(`/knowledge-articles/${props.article.id}/attachments?page=${nextPage}`);
    if (current !== sequence) return;
    const last = Math.max(1, Math.ceil(result.total / result.pageSize));
    if ((latest && last !== nextPage) || nextPage > last) result = await request<KnowledgeAttachmentPage>(`/knowledge-articles/${props.article.id}/attachments?page=${last}`);
    if (current === sequence) { attachments.value = result; page.value = result.page; }
  } catch (value) { if (current === sequence) showListError(value); }
  finally { if (current === sequence) listBusy.value = false; }
}
watch(() => props.article.version, () => {
  const latest = followLatest;
  followLatest = false;
  void load(page.value, latest);
}, { immediate: true });
function choose(event: Event) {
  selected.value = (event.target as HTMLInputElement).files?.[0];
  error.value = undefined;
}
async function upload() {
  if (blocked.value || props.refreshing || props.externalBlocked || (!pending.value && !permitted.value)) return;
  if (!pending.value && (!selected.value || selected.value.size < 1 || selected.value.size > 100 * 1024 * 1024)) {
    showError(new ApiError(400, { code: 'VALIDATION_FAILED', message: '请选择非空且不超过 100 MiB 的文件' }));
    return;
  }
  busy.value = true;
  try {
    if (!pending.value) {
      const body = new FormData();
      body.append('file', selected.value!);
      pending.value = { body, version: props.article.version, key: crypto.randomUUID(), path: `/knowledge-articles/${props.article.id}/attachments`, method: 'POST' };
    }
    error.value = undefined;
    const result = await request<MemberAction>(pending.value.path, pending.value);
    if (!alive) return;
    ElMessage.success(result.replayed ? '此操作此前已完成，正在刷新知识' : pending.value.method === 'DELETE' ? '附件已移除' : '附件上传成功');
    pending.value = undefined;
    selected.value = undefined;
    if (input.value) input.value.value = '';
    followLatest = true;
    emit('refresh');
  } catch (value) {
    if (!alive) return;
    showError(value);
    const failure = error.value!;
    if (failure.status >= 400 && failure.status < 500 && !['REQUEST_IN_PROGRESS', 'RATE_LIMITED'].includes(failure.code)) {
      pending.value = undefined;
      if (['VERSION_CONFLICT', 'INVALID_STATE', 'RESOURCE_NOT_FOUND', 'FORBIDDEN', 'IDEMPOTENCY_CONFLICT'].includes(failure.code)) emit('refresh');
    }
  } finally { if (alive) busy.value = false; }
}
async function remove(item: KnowledgeAttachment) {
  if (blocked.value || refreshingBlocked() || !permitted.value || pending.value) return;
  pending.value = { path: `/knowledge-articles/${props.article.id}/attachments/${item.id}`, method: 'DELETE', version: props.article.version, key: crypto.randomUUID() };
  await upload();
}
function refreshingBlocked() { return props.refreshing || props.externalBlocked; }
async function download(item: KnowledgeAttachment) {
  if (downloadBusy.value) return;
  downloadBusy.value = true;
  downloadError.value = undefined;
  try {
    const blob = await request<Blob>(`/knowledge-attachments/${item.id}`, { binary: true });
    if (!alive) return;
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url; link.download = item.fileName;
    document.body.appendChild(link); link.click(); link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (value) { if (alive) showDownloadError(value); }
  finally { if (alive) downloadBusy.value = false; }
}
function sizeLabel(size: number) { return size < 1024 ? `${size} B` : size < 1024 * 1024 ? `${(size / 1024).toFixed(1)} KiB` : `${(size / (1024 * 1024)).toFixed(1)} MiB`; }
</script>

<template>
  <section class="attachments-card" aria-labelledby="attachments-heading">
    <div class="section-heading"><h3 id="attachments-heading">附件</h3><ElButton :disabled="listBlocked" @click="load()">刷新附件</ElButton></div>
    <ErrorNotice :error="listError" /><ErrorNotice :error="downloadError" />
    <ElButton v-if="listError" :disabled="listBlocked" @click="load()">重新加载附件</ElButton>
    <div v-loading="listBusy" class="attachment-list">
      <p v-if="attachments && !attachments.total" class="hint">暂无附件。</p>
      <article v-for="item in attachments?.items" :key="item.id" class="attachment-item">
        <div class="file-info"><strong>{{ item.fileName }}</strong><span>{{ sizeLabel(item.size) }}</span><small>{{ item.uploaderName }} · {{ new Date(item.createdAt).toLocaleString('zh-CN') }}</small></div>
        <ElButton :disabled="downloadBusy" @click="download(item)">下载</ElButton><ElPopconfirm v-if="permitted" title="从草稿中移除此附件？" @confirm="remove(item)"><template #reference><ElButton :disabled="blocked || refreshing || externalBlocked || !!pending">移除</ElButton></template></ElPopconfirm>
      </article>
    </div>
    <ElPagination v-if="attachments && attachments.total > attachments.pageSize" :current-page="page" :page-size="attachments.pageSize" :total="attachments.total" :disabled="listBusy" layout="total, prev, pager, next" @current-change="load($event)" />
    <div class="upload-area">
      <ErrorNotice :error="error" />
      <ElAlert v-if="pending && error" title="附件操作结果尚未确认，请重试原请求；文件和版本已保留，避免重复上传。" type="info" :closable="false" />
      <template v-if="permitted || pending">
        <fieldset :disabled="busy || !!pending || refreshing || externalBlocked">
          <legend>上传附件</legend>
          <p class="hint">一次一个文件，最大 100 MiB。附件仅供下载，不提供在线预览。</p>
          <label class="file-picker">选择文件 <input ref="input" type="file" aria-label="选择附件文件" @change="choose" /></label>
        </fieldset>
        <div class="submit-row"><ElButton type="primary" :loading="busy" :disabled="blocked || refreshing || externalBlocked || (!pending && !permitted)" @click="upload">{{ waitSeconds ? `${waitSeconds} 秒后重试` : pending ? '重试原操作' : '上传附件' }}</ElButton></div>
      </template>
      <p v-else class="hint">知识已结束，不能上传附件，已有附件仍可下载。<RouterLink to="/knowledge-articles/new">新建知识</RouterLink></p>
    </div>
  </section>
</template>

<style scoped>
.attachments-card { margin-top: 24px; padding: 22px 26px; background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; }
.section-heading, .attachment-item, .file-info { display: flex; align-items: center; gap: 12px; }
.section-heading, .attachment-item { justify-content: space-between; }
h3 { margin: 0; font-size: 16px; }
.attachment-list { min-height: 56px; margin-top: 16px; }
.attachment-item { padding: 16px 0; border-bottom: 1px solid #edf0f6; }
.file-info { flex: 1; min-width: 0; flex-wrap: wrap; font-size: 13px; }
.file-info strong { overflow-wrap: anywhere; }
.file-info small { width: 100%; color: #7b899d; }
.upload-area { margin-top: 20px; padding-top: 20px; border-top: 1px solid #edf0f6; }
fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
legend { font-size: 14px; font-weight: 600; }
.hint { color: #6c7b90; line-height: 1.8; font-size: 13px; }
.file-picker { display: block; margin: 16px 0; font-size: 14px; }
input { margin-left: 12px; max-width: 100%; }
.submit-row { display: flex; justify-content: flex-end; }
.el-pagination, .el-alert { margin-top: 16px; }
</style>
