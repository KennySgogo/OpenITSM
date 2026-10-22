<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import ErrorNotice from '../components/ErrorNotice.vue';
import { ApiError, request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { sessionState } from '../stores/SessionStore';
import type { Ticket, TicketAction, Assignee } from '../types/ApiTypes';
import type { ClassificationSuggestion, CreatedTicket, CreateTicketRequest, TicketTypeOption } from '../types/ApiTypes';

const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const form = ref<FormInstance>();
const model = reactive<CreateTicketRequest>({ title: '', description: '', businessTypeId: '', impactScope: '', priority: 'P3', assigneeId: '' });
const created = ref<CreatedTicket>();
const uncertain = ref(false);
const { error: aiError, busy: aiBusy, blocked: aiBlocked, showError: showAiError } = useFeedback();
const suggestion = ref<ClassificationSuggestion>();
const aiNotice = ref('');
const typeNotice = ref('');
let aiSequence = 0;
let alive = true;
onUnmounted(() => { alive = false; aiSequence++; });
function invalidateSuggestion(message = '') {
  aiSequence++;
  suggestion.value = undefined;
  aiError.value = undefined;
  aiBusy.value = false;
  aiNotice.value = message;
}
watch(() => [model.title, model.description, model.impactScope, model.businessTypeId], () => {
  invalidateSuggestion(suggestion.value || aiBusy.value ? '表单已变化，请重新生成建议。' : '');
  if (model.businessTypeId) typeNotice.value = '';
}, { flush: 'sync' });
const rules: FormRules = Object.fromEntries([
  ['title', '请输入标题'], ['description', '请输入问题描述'],
  ['businessTypeId', '请选择业务类型'], ['impactScope', '请输入影响范围']
].map(([field, message]) => [field, [{ required: true, whitespace: true, message, trigger: 'blur' }]]));

const types = ref<TicketTypeOption[]>([]);
const selectedType = computed(() => types.value.find(type => type.id === model.businessTypeId));
const suggestedType = computed(() => types.value.find(type => type.id === suggestion.value?.businessTypeId));
const assignees = ref<Assignee[]>([]);
const candidateMessage = ref('');
const staff = computed(() => sessionState.session?.user.role !== 'EMPLOYEE');
interface Upload { file: File; visibility: string; done: boolean; error: string }
const uploads = ref<Upload[]>([]);
const uploadBusy = ref(false);
async function loadTypes() {
  types.value = (await request<{ items: TicketTypeOption[] }>('/ticket-types')).items;
  if (model.businessTypeId && !types.value.some(type => type.id === model.businessTypeId)) {
    model.businessTypeId = '';
    typeNotice.value = '之前选择的业务类型已不可用，请重新选择。';
  }
}
async function loadOptions() {
  invalidateSuggestion();
  try { await loadTypes(); }
  catch (value) { showError(value); }
  try { assignees.value = (await request<{ items: Assignee[] }>('/users/assignees')).items; }
  catch { candidateMessage.value = '负责人暂时无法加载，仍可不指定提交。'; }
}
async function generateSuggestion() {
  if (aiBlocked.value || busy.value || uncertain.value || created.value || !model.title.trim() || !model.description.trim()) return;
  const sequence = ++aiSequence;
  aiBusy.value = true; aiError.value = undefined; suggestion.value = undefined; aiNotice.value = '';
  try {
    const result = await request<ClassificationSuggestion>('/ai/classification-suggestions', {
      method: 'POST', body: { title: model.title, description: model.description, impactScope: model.impactScope }
    });
    if (!alive || sequence !== aiSequence || busy.value || created.value || uncertain.value) return;
    if (result.businessTypeId && !types.value.some(type => type.id === result.businessTypeId)) await loadTypes();
    if (!alive || sequence !== aiSequence || busy.value || created.value || uncertain.value) return;
    if (result.businessTypeId && !types.value.some(type => type.id === result.businessTypeId))
      throw new ApiError(400, { code: 'VALIDATION_FAILED', message: '建议的业务类型已不可用，请重新生成或手动选择。' });
    suggestion.value = result;
  } catch (value) { if (alive && sequence === aiSequence) showAiError(value); }
  finally { if (alive && sequence === aiSequence) aiBusy.value = false; }
}
function adoptSuggestion() {
  if (busy.value || uncertain.value || created.value || !suggestedType.value) return;
  const type = suggestedType.value;
  model.businessTypeId = type.id;
  suggestion.value = undefined;
  aiNotice.value = `已采用“${type.name}”，初始优先级保持不变。`;
  typeNotice.value = '';
  form.value?.clearValidate('businessTypeId');
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
      const body = new FormData(); body.append('file', item.file); body.append('visibility', item.visibility);
      await request<TicketAction>(`/tickets/${created.value.id}/attachments`, { method: 'POST', body });
      item.done = true; item.error = '';
    } catch (value) {
      const failure = value as { message: string; status: number; code: string };
      item.error = failure.status === 0 || failure.status >= 500 || failure.code === 'INVALID_RESPONSE' ? `${failure.message}，请先查看工单附件核对结果，再决定是否重试。` : failure.message;
      break;
    }
  }
  uploadBusy.value = false;
}
onMounted(loadOptions);

async function submit() {
  if (blocked.value || uncertain.value || created.value) return;
  busy.value = true;
  invalidateSuggestion();
  try {
    if (!await form.value?.validate().catch(() => false)) return;
    error.value = undefined;
    created.value = await request<CreatedTicket>('/tickets', { method: 'POST', body: { ...model, assigneeId: model.assigneeId || undefined } });
    await uploadFiles();
  } catch (value) {
    showError(value);
    const failure = error.value!;
    uncertain.value = failure.status === 0 || failure.status >= 500 || failure.code === 'INVALID_RESPONSE';
    if (failure.fieldErrors.some(field => field.field === 'businessTypeId')) {
      try { await loadTypes(); } catch { typeNotice.value = '业务类型加载失败，请刷新选项后重新确认。'; }
    }
  } finally { busy.value = false; }
}

function another() {
  if (uploadBusy.value) return;
  uploads.value = [];
  invalidateSuggestion();
  typeNotice.value = '';
  created.value = undefined;
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
      <ElAlert v-if="uncertain" title="提交结果尚未确认，请先到我的工单核对，避免重复建单。" type="warning" :closable="false" />
      <RouterLink v-if="uncertain" to="/tickets?view=MINE">查看我的工单</RouterLink>

      <ElForm ref="form" :model="model" :rules="rules" label-position="top" :disabled="busy" @submit.prevent="submit">
        <ElFormItem label="标题" prop="title"><ElInput v-model="model.title" placeholder="简要描述遇到的问题" /></ElFormItem>
        <ElFormItem label="问题描述" prop="description"><ElInput v-model="model.description" type="textarea" :rows="7" placeholder="请说明问题现象、发生时间及已尝试的操作" /></ElFormItem>
        <section class="create-ai" aria-label="提单前 AI 类型建议">
          <h3>AI 类型建议</h3>
          <p>填写标题和问题描述即可生成建议。点击采用后才填入类型；AI 不可用时可继续手动提单。</p>
          <ElButton :loading="aiBusy" :disabled="aiBlocked || uncertain || !model.title.trim() || !model.description.trim()" @click="generateSuggestion">生成 AI 类型建议</ElButton>
          <ErrorNotice :error="aiError" />
          <p v-if="aiNotice" role="status">{{ aiNotice }}</p>
          <div v-if="suggestion" class="suggestion" aria-live="polite">
            <template v-if="suggestedType"><strong>建议类型：{{ suggestedType.name }}</strong><p>{{ suggestion.reason }}</p><ElButton :disabled="busy || uncertain" @click="adoptSuggestion">采用此类型</ElButton></template>
            <p v-else>没有找到合适的业务类型，请手动选择。</p>
            <p>优先级建议：{{ suggestion.suggestedPriority ?? '无' }}。仅供参考，不自动修改初始优先级。</p>
          </div>
        </section>
        <div class="ticket-fields"><ElFormItem label="业务类型" prop="businessTypeId"><ElSelect v-model="model.businessTypeId" placeholder="请选择业务类型"><ElOption v-for="type in types" :key="type.id" :label="type.name" :value="type.id" /></ElSelect><RouterLink v-if="staff" to="/ticket-types">管理业务类型</RouterLink><ElButton text @click="loadOptions">刷新选项</ElButton></ElFormItem><ElFormItem label="影响范围" prop="impactScope"><ElInput v-model="model.impactScope" placeholder="说明受影响的人员或业务范围" /></ElFormItem></div>
        <p v-if="selectedType" class="selected-type-description">业务说明：{{ selectedType.description || '暂无说明' }}</p>
        <ElAlert v-if="typeNotice" :title="typeNotice" type="warning" :closable="false" />
        <ElFormItem label="初始优先级"><ElSelect v-model="model.priority"><ElOption v-for="value in ['P1','P2','P3','P4']" :key="value" :value="value" :label="value" /></ElSelect></ElFormItem>
        <ElFormItem label="负责人（可不指定）"><ElSelect v-model="model.assigneeId" clearable placeholder="进入未分派队列"><ElOption v-for="user in assignees" :key="user.id" :value="user.id" :label="user.name" /></ElSelect><p>{{ candidateMessage }}</p></ElFormItem>
        <ElFormItem label="附件（单个不超过 10 MiB）"><input type="file" multiple :disabled="busy" @change="selectFiles" /></ElFormItem>
        <p v-for="(item, index) in uploads" :key="index">{{ item.file.name }} {{ item.error }}<ElSelect v-if="staff" v-model="item.visibility"><ElOption value="PUBLIC" label="公开" /><ElOption value="INTERNAL" label="内部" /></ElSelect></p>
      </ElForm>
      <div class="ticket-actions"><ElButton type="primary" :loading="busy" :disabled="blocked || uncertain" @click="submit">{{ waitSeconds ? `${waitSeconds} 秒后重试` : '提交工单' }}</ElButton></div>
    </template>
  </section>
</template>

<style scoped>
.ticket-card { max-width: 880px; padding: 28px; background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; }
.ticket-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 22px; }
.ticket-actions { display: flex; justify-content: flex-end; padding-top: 18px; border-top: 1px solid #edf0f6; }
.ticket-retry { margin-bottom: 20px; }
.create-ai { margin: 0 0 24px; padding: 18px; background: #f6f9fd; border-radius: 6px; }
.create-ai h3 { margin: 0; font-size: 15px; }
.create-ai p { font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.suggestion { margin-top: 16px; padding-top: 16px; border-top: 1px solid #e3e9f2; }
.another-ticket { margin-left: 12px; }
.ticket-receipt { overflow-wrap: anywhere; line-height: 1.8; }
.ticket-receipt p + p { color: #8a97ab; font-size: 12px; }
.selected-type-description { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; margin: 0 0 20px; }
@media (max-width: 700px) { .ticket-fields { grid-template-columns: 1fr; gap: 0; } .ticket-card { padding: 20px; } }
</style>
