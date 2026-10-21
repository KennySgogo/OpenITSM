<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import ErrorNotice from '../components/ErrorNotice.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { sessionState } from '../stores/SessionStore';
import type { Ticket, TicketAction, Assignee } from '../types/ApiTypes';
import type { CreatedTicket, CreateTicketRequest, TicketTypeOption } from '../types/ApiTypes';

const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const form = ref<FormInstance>();
const model = reactive<CreateTicketRequest>({ title: '', description: '', businessTypeId: '', impactScope: '', priority: 'P3', assigneeId: '' });
const pending = ref<{ body: CreateTicketRequest; key: string }>();
const created = ref<CreatedTicket>();
const rules: FormRules = Object.fromEntries([
  ['title', '请输入标题'], ['description', '请输入问题描述'],
  ['businessTypeId', '请选择业务类型'], ['impactScope', '请输入影响范围']
].map(([field, message]) => [field, [{ required: true, whitespace: true, message, trigger: 'blur' }]]));

const types = ref<TicketTypeOption[]>([]);
const selectedType = computed(() => types.value.find(type => type.id === model.businessTypeId));
const assignees = ref<Assignee[]>([]);
const candidateMessage = ref('');
const staff = computed(() => sessionState.session?.user.role !== 'EMPLOYEE');
interface Upload { file: File; visibility: string; done: boolean; error: string; pending?: { body: FormData; version: number; key: string } }
const uploads = ref<Upload[]>([]);
const uploadBusy = ref(false);
async function loadOptions() {
  try { types.value = (await request<{ items: TicketTypeOption[] }>('/ticket-types')).items; }
  catch (value) { showError(value); }
  try { assignees.value = (await request<{ items: Assignee[] }>('/users/assignees')).items; }
  catch { candidateMessage.value = '负责人暂时无法加载，仍可不指定提交。'; }
}
function selectFiles(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? []);
  uploads.value = files.map(file => ({ file, visibility: 'PUBLIC', done: false, error: file.size < 1 || file.size > 10 * 1024 * 1024 ? '文件须为 1 字节～10 MiB' : '' }));
}
async function uploadFiles() {
  if (!created.value || uploadBusy.value) return;
  uploadBusy.value = true;
  for (const item of uploads.value) {
    if (item.done || item.file.size < 1 || item.file.size > 10 * 1024 * 1024) continue;
    try {
      if (!item.pending) {
        const ticket = await request<Ticket>(`/tickets/${created.value.id}`);
        const body = new FormData(); body.append('file', item.file); body.append('visibility', item.visibility);
        item.pending = { body, version: ticket.version, key: crypto.randomUUID() };
      }
      await request<TicketAction>(`/tickets/${created.value.id}/attachments`, { method: 'POST', ...item.pending });
      item.done = true; item.error = ''; item.pending = undefined;
    } catch (value) {
      const failure = value as { message: string; status: number; code: string };
      item.error = failure.message;
      if (failure.status >= 400 && failure.status < 500 && !['REQUEST_IN_PROGRESS', 'RATE_LIMITED'].includes(failure.code)) item.pending = undefined;
      break;
    }
  }
  uploadBusy.value = false;
}
onMounted(loadOptions);

async function submit() {
  if (blocked.value || created.value) return;
  // Keep a detached snapshot until an uncertain submission has been resolved.
  busy.value = true;
  try {
    if (!pending.value) {
      if (!await form.value?.validate().catch(() => false)) return;
      pending.value = { body: { ...model, assigneeId: model.assigneeId || undefined }, key: crypto.randomUUID() };
    }
    error.value = undefined;
    created.value = await request<CreatedTicket>('/tickets', { method: 'POST', ...pending.value });
    pending.value = undefined;
    await uploadFiles();
  } catch (value) {
    showError(value);
    const failure = error.value!;
    if (failure.status >= 400 && failure.status < 500 && !['REQUEST_IN_PROGRESS', 'RATE_LIMITED'].includes(failure.code)) {
      pending.value = undefined;
    }
  } finally { busy.value = false; }
}

function another() {
  if (uploadBusy.value) return;
  uploads.value = [];
  created.value = undefined;
  pending.value = undefined;
  error.value = undefined;
  Object.assign(model, { title: '', description: '', businessTypeId: '', impactScope: '', priority: 'P3', assigneeId: '' });
}
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">内部服务</span><h1>提交工单</h1><p>描述你遇到的问题，方便 IT 团队了解情况。</p></div></div>
  <section class="ticket-card">
    <ElResult v-if="created" icon="success" title="工单提交成功">
      <template #sub-title><div class="ticket-receipt" aria-live="polite"><p>工单编号：<strong>{{ created.number }}</strong></p><p>工单 ID：{{ created.id }}</p></div></template>
      <template #extra><div v-if="uploads.length" class="upload-summary"><p>工单已创建。附件独立上传，失败时只重试附件。</p><p v-for="(item, index) in uploads" :key="index">{{ item.file.name }}：{{ item.done ? '上传成功' : item.error || '等待上传' }}</p><ElButton :loading="uploadBusy" :disabled="uploadBusy || uploads.every(item => item.done)" @click="uploadFiles">重试未成功附件</ElButton></div><RouterLink :to="`/tickets/${created.id}`"><ElButton type="primary">查看工单</ElButton></RouterLink><ElButton class="another-ticket" @click="another">继续提单</ElButton></template>
    </ElResult>
    <template v-else>
      <ErrorNotice :error="error" />
      <ElAlert v-if="pending && error" class="ticket-retry" title="提交结果尚未确认，请重试确认。重试会保留本次填写内容，避免重复建单。" type="info" :closable="false" />
      <ElForm ref="form" :model="model" :rules="rules" label-position="top" :disabled="busy || !!pending" @submit.prevent="submit">
        <ElFormItem label="标题" prop="title"><ElInput v-model="model.title" placeholder="简要描述遇到的问题" /></ElFormItem>
        <ElFormItem label="问题描述" prop="description"><ElInput v-model="model.description" type="textarea" :rows="7" placeholder="请说明问题现象、发生时间及已尝试的操作" /></ElFormItem>
        <div class="ticket-fields"><ElFormItem label="业务类型" prop="businessTypeId"><ElSelect v-model="model.businessTypeId" placeholder="请选择业务类型"><ElOption v-for="type in types" :key="type.id" :label="type.name" :value="type.id" /></ElSelect><RouterLink v-if="staff" to="/ticket-types">管理业务类型</RouterLink><ElButton text @click="loadOptions">刷新选项</ElButton></ElFormItem><ElFormItem label="影响范围" prop="impactScope"><ElInput v-model="model.impactScope" placeholder="说明受影响的人员或业务范围" /></ElFormItem></div>
        <p v-if="selectedType" class="selected-type-description">业务说明：{{ selectedType.description || '暂无说明' }}</p>
        <ElFormItem label="初始优先级"><ElSelect v-model="model.priority"><ElOption v-for="value in ['P1','P2','P3','P4']" :key="value" :value="value" :label="value" /></ElSelect></ElFormItem>
        <ElFormItem label="负责人（可不指定）"><ElSelect v-model="model.assigneeId" clearable placeholder="进入未分派队列"><ElOption v-for="user in assignees" :key="user.id" :value="user.id" :label="user.name" /></ElSelect><p>{{ candidateMessage }}</p></ElFormItem>
        <ElFormItem label="附件（单个不超过 10 MiB）"><input type="file" multiple :disabled="busy || !!pending" @change="selectFiles" /></ElFormItem>
        <p v-for="(item, index) in uploads" :key="index">{{ item.file.name }} {{ item.error }}<ElSelect v-if="staff" v-model="item.visibility"><ElOption value="PUBLIC" label="公开" /><ElOption value="INTERNAL" label="内部" /></ElSelect></p>
      </ElForm>
      <div class="ticket-actions"><ElButton type="primary" :loading="busy" :disabled="blocked" @click="submit">{{ waitSeconds ? `${waitSeconds} 秒后重试` : pending ? '重试提交' : '提交工单' }}</ElButton></div>
    </template>
  </section>
</template>

<style scoped>
.ticket-card { max-width: 880px; padding: 28px; background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; }
.ticket-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 22px; }
.ticket-actions { display: flex; justify-content: flex-end; padding-top: 18px; border-top: 1px solid #edf0f6; }
.ticket-retry { margin-bottom: 20px; }
.another-ticket { margin-left: 12px; }
.ticket-receipt { overflow-wrap: anywhere; line-height: 1.8; }
.ticket-receipt p + p { color: #8a97ab; font-size: 12px; }
.selected-type-description { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; margin: 0 0 20px; }
@media (max-width: 700px) { .ticket-fields { grid-template-columns: 1fr; gap: 0; } .ticket-card { padding: 20px; } }
</style>
