<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import ErrorNotice from '../components/ErrorNotice.vue';
import TicketActions from '../components/TicketActions.vue';
import TicketCommunication from '../components/TicketCommunication.vue';
import TicketAttachments from '../components/TicketAttachments.vue';
import TicketClassification from '../components/TicketClassification.vue';
import TicketSimilarIssues from '../components/TicketSimilarIssues.vue';
import { sessionState } from '../stores/SessionStore';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import { ticketStatusLabels } from '../types/ApiTypes';
import type { Ticket } from '../types/ApiTypes';
const route = useRoute();
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const ticket = ref<Ticket>();
const actionLocked = ref(false);
const communicationLocked = ref(false);
const attachmentLocked = ref(false);
const classificationLocked = ref(false);
let sequence = 0;
onUnmounted(() => { sequence++; });
async function load(keepTicket = false) {
  const current = ++sequence;
  if (!keepTicket) ticket.value = undefined;
  busy.value = true;
  error.value = undefined;
  try {
    const result = await request<Ticket>(`/tickets/${encodeURIComponent(String(route.params.id))}`);
    if (current === sequence) ticket.value = result;
  } catch (value) { if (current === sequence) { ticket.value = undefined; showError(value); } }
  finally { if (current === sequence) busy.value = false; }
}
watch(() => route.params.id, () => load(), { immediate: true });
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">服务工单</span><h1>{{ ticket?.number ?? '工单详情' }}</h1></div><ElButton :disabled="blocked || actionLocked || communicationLocked || attachmentLocked || classificationLocked" @click="load(true)">刷新详情</ElButton><RouterLink to="/tickets?view=MINE"><ElButton>返回我的工单</ElButton></RouterLink></div>
  <ErrorNotice :error="error" />
  <div v-if="error" class="detail-retry"><ElButton :disabled="blocked" @click="load()">{{ waitSeconds ? `${waitSeconds} 秒后重试` : '重新加载' }}</ElButton></div>
  <section v-loading="busy" class="ticket-detail">
    <template v-if="ticket">
      <div class="detail-heading"><h2>{{ ticket.title }}</h2><ElTag>{{ ticketStatusLabels[ticket.status] }}</ElTag><ElTag effect="plain">{{ ticket.priority }}</ElTag></div>
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem label="提单人">{{ ticket.requesterName }}</ElDescriptionsItem><ElDescriptionsItem label="负责人">{{ ticket.assigneeName ?? '未分派' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="业务类型">{{ ticket.businessType }}</ElDescriptionsItem><ElDescriptionsItem label="影响范围">{{ ticket.impactScope }}</ElDescriptionsItem>
        <ElDescriptionsItem label="创建时间">{{ new Date(ticket.createdAt).toLocaleString('zh-CN') }}</ElDescriptionsItem><ElDescriptionsItem v-if="ticket.closedAt" label="关闭时间">{{ new Date(ticket.closedAt).toLocaleString('zh-CN') }}</ElDescriptionsItem>
      </ElDescriptions>
      <h3>问题描述</h3><p class="ticket-text">{{ ticket.description }}</p>
      <template v-if="ticket.rejectionReason"><h3>拒绝原因</h3><p class="ticket-text">{{ ticket.rejectionReason }}</p><p>{{ ticket.rejectedAt ? new Date(ticket.rejectedAt).toLocaleString('zh-CN') : '' }}</p></template>
      <template v-if="ticket.resolutionSummary"><h3>解决摘要</h3><p class="ticket-text">{{ ticket.resolutionSummary }}</p></template>
    </template>
  </section>
  <TicketClassification v-if="ticket" :key="ticket.id" :ticket="ticket" :refreshing="busy" :external-blocked="actionLocked || communicationLocked || attachmentLocked" @lock="classificationLocked = $event" @refresh="load(true)" />
  <TicketAttachments v-if="ticket" :key="ticket.id" :ticket="ticket" :refreshing="busy" :external-blocked="actionLocked || communicationLocked || classificationLocked" @lock="attachmentLocked = $event" @refresh="load(true)" />
  <TicketSimilarIssues v-if="ticket && sessionState.session && sessionState.session.user.role !== 'EMPLOYEE'" :key="ticket.id" :ticket="ticket" :refreshing="busy" />
  <TicketActions v-if="ticket" :key="ticket.id" :ticket="ticket" :refreshing="busy" :external-blocked="communicationLocked || attachmentLocked || classificationLocked" @lock="actionLocked = $event" @refresh="load(true)" />
  <TicketCommunication v-if="ticket" :key="ticket.id" :ticket="ticket" :refreshing="busy" :external-blocked="actionLocked || attachmentLocked || classificationLocked" @lock="communicationLocked = $event" @refresh="load(true)" />
</template>

<style scoped>
.ticket-detail { min-height: 280px; padding: 26px; background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; }
.detail-heading { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 25px; }
.detail-heading h2 { flex: 1; margin: 0; font-size: 21px; overflow-wrap: anywhere; }
h3 { margin: 28px 0 12px; font-size: 15px; }
.ticket-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; }
.detail-retry { margin: 16px 0; }
</style>
