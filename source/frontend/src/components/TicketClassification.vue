<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import ErrorNotice from './ErrorNotice.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { sessionState } from '../stores/SessionStore';
import type { ClassificationSuggestion, Ticket, TicketAction, TicketTypeOption } from '../types/ApiTypes';

const props = defineProps<{ ticket: Ticket; refreshing: boolean; externalBlocked?: boolean }>();
const emit = defineEmits<{ refresh: []; lock: [value: boolean] }>();
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const types = ref<TicketTypeOption[]>([]);
const typesReady = ref(false);
const selected = ref(props.ticket.businessTypeId);
const suggestion = ref<ClassificationSuggestion>();
const permitted = computed(() => {
  const user = sessionState.session?.user;
  if (!user || !['NEW', 'IN_PROGRESS', 'ON_HOLD'].includes(props.ticket.status)) return false;
  return user.role === 'ADMIN' || (user.role === 'ENGINEER' && props.ticket.assigneeId === user.id)
    || (props.ticket.status === 'NEW' && props.ticket.requesterId === user.id);
});
const suggestedType = computed(() => types.value.find(type => type.id === suggestion.value?.businessTypeId));
const selectedType = computed(() => types.value.find(type => type.id === selected.value));
watch(() => busy.value, value => emit('lock', value), { immediate: true });
onUnmounted(() => emit('lock', false));
watch(() => props.ticket.version, () => {
  selected.value = props.ticket.businessTypeId;
  suggestion.value = undefined;

});
watch(permitted, value => { if (value && !typesReady.value) void loadTypes(); }, { immediate: true });

async function loadTypes() {
  if (busy.value) return;
  busy.value = true; error.value = undefined;
  try {
    types.value = (await request<{ items: TicketTypeOption[] }>('/ticket-types')).items;
    typesReady.value = true;
  } catch (value) { showError(value); }
  finally { busy.value = false; }
}
async function generate() {
  if (!permitted.value || blocked.value || props.refreshing || props.externalBlocked) return;
  busy.value = true; error.value = undefined; suggestion.value = undefined;
  try {
    suggestion.value = await request<ClassificationSuggestion>(`/tickets/${props.ticket.id}/ai/classification-suggestions`, { method: 'POST' });
  } catch (value) { showError(value); }
  finally { busy.value = false; }
}
async function confirm() {
  if (!permitted.value || blocked.value || props.refreshing || props.externalBlocked || !typesReady.value
    || !types.value.some(type => type.id === selected.value)) return;
  busy.value = true; error.value = undefined;
  try {
    await request(`/tickets/${props.ticket.id}/classifications`, { method: 'POST', body: { businessTypeId: selected.value } });
    ElMessage.success('业务类型已更新');
    suggestion.value = undefined;
    emit('refresh');
  } catch (value) {
    showError(value);
    if (error.value!.code === 'VALIDATION_FAILED') typesReady.value = false;
    emit('refresh');
  } finally { busy.value = false; }
}
</script>

<template>
  <section v-if="permitted" class="ticket-classification">
    <h3>业务类型与 AI 建议</h3>
    <p>可以直接确认已有类型；AI 不可用时仍可人工处理。建议不会自动修改工单。</p>
    <ErrorNotice :error="error" />

    <div class="classification-controls">
      <ElSelect v-model="selected" placeholder="选择已有业务类型" aria-label="业务类型" :disabled="blocked || refreshing || externalBlocked || !typesReady" style="width: 260px">
        <ElOption v-for="type in types" :key="type.id" :value="type.id" :label="type.name" />
      </ElSelect>
      <ElButton type="primary" :loading="busy" :disabled="blocked || refreshing || externalBlocked || !typesReady || !types.some(type => type.id === selected)" @click="confirm">确认业务类型</ElButton>
      <ElButton :disabled="blocked || refreshing || externalBlocked" @click="generate">生成 AI 建议</ElButton>
      <ElButton :disabled="blocked || refreshing || externalBlocked" @click="loadTypes">刷新类型</ElButton>
    </div>
    <p v-if="selectedType">业务说明：{{ selectedType.description || '暂无说明' }}</p>
    <p v-if="typesReady && !types.length">暂无可用业务类型，请联系工程师或管理员创建。</p>
    <div v-if="suggestion" class="suggestion">
      <strong>建议尚未生效</strong>
      <p>业务类型：{{ suggestion.businessTypeId ? (suggestedType?.name ?? suggestion.businessTypeId) : '暂无合适建议' }}</p>
      <p v-if="suggestion.reason">依据：{{ suggestion.reason }}</p>
      <p>优先级建议：{{ suggestion.suggestedPriority ?? '无' }}。仅管理员可在工单操作中填写原因并单独调整优先级。</p>
      <ElButton v-if="suggestion.businessTypeId && suggestedType" :disabled="blocked || refreshing || externalBlocked" @click="selected = suggestion.businessTypeId">选用建议类型</ElButton>
    </div>
    <p v-if="waitSeconds">{{ waitSeconds }} 秒后重试。</p>
  </section>
</template>

<style scoped>
.ticket-classification { margin-top: 24px; padding: 22px 26px; background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; }
h3 { margin: 0 0 12px; font-size: 15px; }
p { line-height: 1.8; overflow-wrap: anywhere; }
.classification-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.suggestion { margin-top: 18px; padding: 14px 18px; background: #f6f9fd; border-radius: 6px; }
.suggestion p { margin: 8px 0; }
</style>
