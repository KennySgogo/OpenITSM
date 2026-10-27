<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import ErrorNotice from './ErrorNotice.vue';
import TicketReplyDraft from './TicketReplyDraft.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { sessionState } from '../stores/SessionStore';
import { canReply, historyActionLabel, historyChanges } from '../utils/TicketCommunication';
import type { Ticket, TicketAction, TicketReplyPage, TicketOperationPage } from '../types/ApiTypes';

const props = defineProps<{ ticket: Ticket; refreshing: boolean; externalBlocked: boolean }>();
const emit = defineEmits<{ refresh: []; lock: [value: boolean] }>();
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const { error: repliesError, busy: repliesBusy, blocked: repliesBlocked, showError: showRepliesError } = useFeedback();
const { error: operationsError, busy: operationsBusy, blocked: operationsBlocked, showError: showOperationsError } = useFeedback();
const replies = ref<TicketReplyPage>();
const operations = ref<TicketOperationPage>();
const replyPage = ref(1);
const operationPage = ref(1);
let replySequence = 0;
let operationSequence = 0;
let alive = true;
let followLatest = false;
onUnmounted(() => { alive = false; replySequence++; operationSequence++; emit('lock', false); });
const staff = computed(() => ['ENGINEER', 'ADMIN'].includes(sessionState.session?.user.role ?? ''));
const permitted = computed(() => !!sessionState.session && canReply(props.ticket, sessionState.session.user, model.internal));
const form = ref<FormInstance>();
const model = reactive({ content: '', internal: false });
const draftVersion = ref<number>();
watch(() => model.content, value => { if (!value.trim()) draftVersion.value = undefined; }, { flush: 'sync' });
function adoptDraft(content: string, version: number) {
  if (model.content.trim() || props.refreshing || props.externalBlocked || busy.value || version !== props.ticket.version) return;
  model.internal = false; model.content = content; draftVersion.value = version;
}
const rules: FormRules = { content: [{ required: true, whitespace: true, message: '请填写回复内容', trigger: 'blur' }] };
watch(() => busy.value, value => emit('lock', value), { immediate: true });

async function loadReplies(page = replyPage.value, latest = false) {
  const current = ++replySequence;
  replyPage.value = page;
  repliesBusy.value = true;
  repliesError.value = undefined;
  replies.value = undefined;
  try {
    let result = await request<TicketReplyPage>(`/tickets/${props.ticket.id}/replies?page=${page}`);
    if (current !== replySequence) return;
    const last = Math.max(1, Math.ceil(result.total / result.pageSize));
    if ((latest && last !== page) || (page > last)) {
      result = await request<TicketReplyPage>(`/tickets/${props.ticket.id}/replies?page=${last}`);
    }
    if (current === replySequence) { replies.value = result; replyPage.value = result.page; }
  } catch (value) { if (current === replySequence) showRepliesError(value); }
  finally { if (current === replySequence) repliesBusy.value = false; }
}
async function loadOperations(page = operationPage.value, latest = false) {
  const current = ++operationSequence;
  operationPage.value = page;
  operationsBusy.value = true;
  operationsError.value = undefined;
  operations.value = undefined;
  try {
    let result = await request<TicketOperationPage>(`/tickets/${props.ticket.id}/operations?page=${page}`);
    if (current !== operationSequence) return;
    const last = Math.max(1, Math.ceil(result.total / result.pageSize));
    if ((latest && last !== page) || (page > last)) {
      result = await request<TicketOperationPage>(`/tickets/${props.ticket.id}/operations?page=${last}`);
    }
    if (current === operationSequence) { operations.value = result; operationPage.value = result.page; }
  } catch (value) { if (current === operationSequence) showOperationsError(value); }
  finally { if (current === operationSequence) operationsBusy.value = false; }
}
watch(() => props.ticket.version, () => {
  const latest = followLatest;
  followLatest = false;
  void loadReplies(replyPage.value, latest);
  void loadOperations(operationPage.value, latest);
}, { immediate: true });

async function submit() {
  if (blocked.value || props.refreshing || props.externalBlocked || !permitted.value) return;
  busy.value = true;
  try {
    if (!await form.value?.validate().catch(() => false)) return;
    error.value = undefined;
    await request<TicketAction>(`/tickets/${props.ticket.id}/${model.internal ? 'internal-notes' : 'replies'}`, {
      method: 'POST', body: { content: model.content, ...(draftVersion.value === undefined ? {} : { basedOnVersion: draftVersion.value }) }
    });
    if (!alive) return;
    ElMessage.success(model.internal ? '内部备注已添加' : '公开回复已发表');
    model.content = '';
    form.value?.clearValidate();
    followLatest = true;
    emit('refresh');
  } catch (value) {
    if (!alive) return;
    showError(value);
    const failure = error.value!;
    if (failure.status >= 400 && failure.status < 500 && !['RATE_LIMITED'].includes(failure.code)) {
      if (['INVALID_STATE', 'RESOURCE_NOT_FOUND', 'FORBIDDEN'].includes(failure.code)) emit('refresh');
    }
  } finally { if (alive) busy.value = false; }
}
</script>

<template>
  <section class="communication-card" aria-labelledby="replies-heading">
    <div class="section-heading"><h3 id="replies-heading">沟通记录</h3><ElButton :disabled="repliesBlocked" @click="loadReplies()">刷新回复</ElButton></div>
    <ErrorNotice :error="repliesError" />
    <div v-if="repliesError" class="retry-row"><ElButton :disabled="repliesBlocked" @click="loadReplies()">重新加载回复</ElButton></div>
    <div v-loading="repliesBusy" class="timeline-content">
      <p v-if="replies && !replies.total" class="muted">暂无回复。</p>
      <article v-for="reply in replies?.items" :key="reply.id" class="reply-item" :class="{ internal: reply.visibility === 'INTERNAL' }">
        <div class="item-heading"><strong>{{ reply.authorName }}</strong><ElTag size="small" :type="reply.visibility === 'INTERNAL' ? 'warning' : 'info'">{{ reply.visibility === 'INTERNAL' ? '内部备注' : '公开回复' }}</ElTag><time>{{ new Date(reply.createdAt).toLocaleString('zh-CN') }}</time></div>
        <p class="content-text">{{ reply.content }}</p>
      </article>
    </div>
    <ElPagination v-if="replies && replies.total > replies.pageSize" :current-page="replyPage" :page-size="replies.pageSize" :total="replies.total" :disabled="repliesBusy" layout="total, prev, pager, next" @current-change="loadReplies($event)" />
    <div class="reply-editor">
      <ErrorNotice :error="error" />
      <TicketReplyDraft v-if="staff" :ticket="ticket" :disabled="busy || refreshing || externalBlocked || ['CLOSED', 'REJECTED'].includes(ticket.status)" :has-reply-text="!!model.content.trim()" @adopt="adoptDraft" />
      <p v-if="draftVersion !== undefined && draftVersion !== ticket.version" role="status">草稿对应的工单已变化，请保留文字并重新核对、生成；旧草稿不能直接发送。</p>

      <template v-if="ticket.status !== 'CLOSED'">
        <ElForm ref="form" :model="model" :rules="rules" label-position="top" :disabled="busy || refreshing || externalBlocked" @submit.prevent="submit">
          <ElFormItem v-if="staff" label="发表方式"><ElRadioGroup v-model="model.internal" aria-label="发表方式"><ElRadioButton :value="false">公开回复</ElRadioButton><ElRadioButton :value="true">内部备注</ElRadioButton></ElRadioGroup></ElFormItem>
          <p class="visibility-hint">{{ model.internal ? '仅工程师和管理员可见，提单员工不可见。' : '公开回复对工单可见者开放，包含提单人。' }}</p>
          <ElFormItem :label="model.internal ? '备注内容' : '回复内容'" prop="content"><ElInput v-model="model.content" type="textarea" :rows="4" :placeholder="model.internal ? '填写内部备注' : '填写公开回复'" /></ElFormItem>
        </ElForm>
        <div class="submit-row"><ElButton type="primary" :loading="busy" :disabled="blocked || refreshing || externalBlocked || !permitted" @click="submit">{{ waitSeconds ? `${waitSeconds} 秒后重试` : model.internal ? '添加内部备注' : '发表公开回复' }}</ElButton></div>
      </template>
      <template v-else><p class="muted">工单已关闭，不能追加回复。<RouterLink to="/tickets/new">新建工单</RouterLink></p><ElInput v-if="model.content" v-model="model.content" aria-label="未发送的回复内容" type="textarea" :rows="4" readonly /></template>
    </div>
  </section>
  <section class="communication-card" aria-labelledby="operations-heading">
    <div class="section-heading"><h3 id="operations-heading">操作记录</h3><ElButton :disabled="operationsBlocked" @click="loadOperations()">刷新操作记录</ElButton></div>
    <ErrorNotice :error="operationsError" />
    <div v-if="operationsError" class="retry-row"><ElButton :disabled="operationsBlocked" @click="loadOperations()">重新加载操作记录</ElButton></div>
    <div v-loading="operationsBusy" class="timeline-content">
      <p v-if="operations && !operations.total" class="muted">暂无操作记录。</p>
      <article v-for="event in operations?.items" :key="event.id" class="operation-item">
        <div class="item-heading"><strong>{{ historyActionLabel(event.action) }}</strong><span>{{ event.actorName ?? '系统' }}</span><time>{{ new Date(event.createdAt).toLocaleString('zh-CN') }}</time></div>
        <p v-for="(change, index) in historyChanges(event)" :key="index" class="content-text">{{ change }}</p>
        <p v-if="event.details.fileName" class="content-text">附件：{{ event.details.fileName }}</p>
        <p v-if="staff && event.details.reason" class="content-text">原因：{{ event.details.reason }}</p>
      </article>
    </div>
    <ElPagination v-if="operations && operations.total > operations.pageSize" :current-page="operationPage" :page-size="operations.pageSize" :total="operations.total" :disabled="operationsBusy" layout="total, prev, pager, next" @current-change="loadOperations($event)" />
  </section>
</template>

<style scoped>
.communication-card { margin-top: 24px; padding: 22px 26px; background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; }
.section-heading, .item-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.section-heading { justify-content: space-between; margin-bottom: 18px; }
h3 { margin: 0; font-size: 16px; }
.timeline-content { min-height: 56px; }
.reply-item, .operation-item { padding: 18px 0; border-bottom: 1px solid #edf0f6; }
.reply-item.internal { padding: 18px 16px; background: #fffaf0; border-left: 3px solid #e6a23c; }
.item-heading { font-size: 13px; }
time { margin-left: auto; color: #7b899d; font-size: 12px; }
.content-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.85; margin: 10px 0 0; }
.reply-editor { margin-top: 24px; border-top: 1px solid #edf0f6; padding-top: 20px; }
.visibility-hint, .muted { color: #6c7b90; line-height: 1.8; font-size: 13px; }
.submit-row { display: flex; justify-content: flex-end; }
.el-pagination, .retry-row { margin-top: 16px; }
.el-alert { margin-bottom: 16px; }
</style>
