<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import ErrorNotice from './ErrorNotice.vue';
import AssigneeRecommendations from './AssigneeRecommendations.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { sessionState } from '../stores/SessionStore';
import { ticketOperations, ticketOperationLabels } from '../utils/TicketPolicy';
import type { TicketOperation } from '../utils/TicketPolicy';
import type { Ticket, TicketAction, Assignee } from '../types/ApiTypes';

const props = defineProps<{ ticket: Ticket; refreshing: boolean; externalBlocked?: boolean }>();
const emit = defineEmits<{ refresh: []; lock: [value: boolean] }>();
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const now = ref(Date.now());
const timer = window.setInterval(() => { now.value = Date.now(); }, 1000);
onUnmounted(() => window.clearInterval(timer));
const actions = computed(() => sessionState.session ? ticketOperations(props.ticket, sessionState.session.user, now.value) : []);
const dialog = ref(false);
const operation = ref<TicketOperation>('claim');
const form = ref<FormInstance>();
const model = reactive({ assigneeId: '', reason: '', summary: '', priority: 'P3' });
const assignees = ref<Assignee[]>([]);
const candidatesReady = ref(false);
watch(() => busy.value, value => emit('lock', value), { immediate: true });
onUnmounted(() => emit('lock', false));
const choosingAssignee = computed(() => ['assignments', 'transfers'].includes(operation.value));
const needsReason = computed(() => ['suspensions', 'reopen', 'priority-changes', 'rejections'].includes(operation.value)
  || (operation.value === 'close' && props.ticket.requesterId !== sessionState.session?.user.id));
const rules: FormRules = {
  assigneeId: [{ required: true, message: '请选择处理人', trigger: 'change' }],
  reason: [{ required: true, whitespace: true, message: '请填写原因', trigger: 'blur' }],
  summary: [{ required: true, whitespace: true, message: '请填写公开解决摘要', trigger: 'blur' }],
  priority: [{ required: true, message: '请选择优先级', trigger: 'change' }]
};
async function loadAssignees() {
  busy.value = true;
  error.value = undefined;
  candidatesReady.value = false;
  try {
    assignees.value = (await request<{ items: Assignee[]; asOf: string }>('/users/assignees')).items
      .filter(user => operation.value !== 'transfers' || user.id !== props.ticket.assigneeId);
    candidatesReady.value = true;
  } catch (value) { showError(value); }
  finally { busy.value = false; }
}
async function open(action: TicketOperation, suggestedId?: string) {
  if (blocked.value || props.refreshing || props.externalBlocked || !sessionState.session || !ticketOperations(props.ticket, sessionState.session.user).includes(action)) return;
  operation.value = action;
  Object.assign(model, { assigneeId: '', reason: '', summary: '', priority: props.ticket.priority });

  error.value = undefined;
  dialog.value = true;
  if (choosingAssignee.value) {
    const ticketId = props.ticket.id;
    await loadAssignees();
    if (dialog.value && props.ticket.id === ticketId && operation.value === action && suggestedId && assignees.value.some(user => user.id === suggestedId)) model.assigneeId = suggestedId;
  }
}
async function submit() {
  if (blocked.value || props.refreshing || props.externalBlocked || (choosingAssignee.value && !candidatesReady.value)) return;
  if (!await form.value?.validate().catch(() => false)) return;
  let body: unknown;
  if (choosingAssignee.value) body = { assigneeId: model.assigneeId };
  else if (operation.value === 'resolutions') body = { summary: model.summary };
  else if (operation.value === 'priority-changes') body = { priority: model.priority, reason: model.reason };
  else if (needsReason.value) body = { reason: model.reason };
  busy.value = true; error.value = undefined;
  try {
    await request(`/tickets/${props.ticket.id}/${operation.value}`, { method: 'POST', body });
    ElMessage.success('工单操作已完成');
    dialog.value = false;
    emit('refresh');
  } catch (value) {
    showError(value);
    dialog.value = false;
    emit('refresh');
  } finally { busy.value = false; }
}
</script>

<template>
  <section class="ticket-actions">
    <AssigneeRecommendations v-if="sessionState.session && sessionState.session.user.role !== 'EMPLOYEE'" :endpoint="`/tickets/${ticket.id}/ai/assignee-recommendations`" :invalidation-key="`${ticket.version}:${model.assigneeId}:${dialog}`" :disabled="refreshing || busy || externalBlocked" :can-adopt="actions.includes('assignments') || actions.includes('transfers')" @adopt="open(actions.includes('assignments') ? 'assignments' : 'transfers', $event.assigneeId)" />
    <h3>工单操作</h3>
    <ErrorNotice v-if="!dialog" :error="error" />
    <div class="action-buttons"><ElButton v-for="action in actions" :key="action" :disabled="blocked || refreshing || externalBlocked" @click="open(action)">{{ ticketOperationLabels[action] }}</ElButton></div>
    <p v-if="!actions.length">当前角色或状态下无可用操作。</p>
    <ElDialog v-model="dialog" :title="ticketOperationLabels[operation]" width="500px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="(done: () => void) => { if (!busy) done(); }" destroy-on-close>
      <p>{{ ticket.number }} · {{ ticket.title }}</p>
      <ErrorNotice :error="error" />

      <ElForm ref="form" :model="model" :rules="rules" label-position="top" :disabled="busy" @submit.prevent="submit">
        <ElFormItem v-if="choosingAssignee" label="目标负责人" prop="assigneeId"><ElSelect v-model="model.assigneeId" placeholder="请选择处理人" aria-label="目标负责人"><ElOption v-for="user in assignees" :key="user.id" :value="user.id" :label="`${user.name}（未解决 ${user.unresolvedCount}，待确认 ${user.pendingConfirmationCount}）`" /></ElSelect></ElFormItem>
        <ElFormItem v-if="operation === 'priority-changes'" label="优先级" prop="priority"><ElSelect v-model="model.priority" aria-label="优先级"><ElOption v-for="priority in ['P1', 'P2', 'P3', 'P4']" :key="priority" :value="priority" :label="priority" /></ElSelect></ElFormItem>
        <ElFormItem v-if="needsReason" label="操作原因" prop="reason"><ElInput v-model="model.reason" type="textarea" :rows="4" placeholder="请填写操作原因" /></ElFormItem>
        <ElFormItem v-if="operation === 'resolutions'" label="公开解决摘要" prop="summary"><ElInput v-model="model.summary" type="textarea" :rows="5" placeholder="请填写公开解决摘要，提单人可见" /></ElFormItem>
      </ElForm>
      <p v-if="operation === 'close'">确认关闭此工单。</p>
      <p v-if="operation === 'reopen'">重开后恢复为处理中，已关闭工单仅限关闭后 7 天内重开。</p>
      <template #footer>
        <ElButton :disabled="busy" @click="dialog = false">取消</ElButton>
        <ElButton v-if="choosingAssignee && !candidatesReady" :disabled="blocked" @click="loadAssignees">重新加载处理人</ElButton>
        <ElButton type="primary" :loading="busy" :disabled="blocked || refreshing || externalBlocked || (choosingAssignee && !candidatesReady)" @click="submit">{{ waitSeconds ? `${waitSeconds} 秒后重试` : '确认操作' }}</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.ticket-actions { margin-top: 24px; padding: 22px 26px; background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; }
h3 { margin: 0 0 18px; font-size: 15px; }
.action-buttons { display: flex; flex-wrap: wrap; gap: 10px; }
.action-buttons .el-button { margin-left: 0; }
.el-select { width: 100%; }
p { overflow-wrap: anywhere; line-height: 1.8; }
</style>
