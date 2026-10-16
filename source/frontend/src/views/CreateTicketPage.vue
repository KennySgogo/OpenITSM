<script setup lang="ts">
import { reactive, ref } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import ErrorNotice from '../components/ErrorNotice.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import type { CreatedTicket, CreateTicketRequest } from '../types/ApiTypes';

const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const form = ref<FormInstance>();
const model = reactive<CreateTicketRequest>({ title: '', description: '', businessType: '', impactScope: '' });
const pending = ref<{ body: CreateTicketRequest; key: string }>();
const created = ref<CreatedTicket>();
const rules: FormRules = Object.fromEntries([
  ['title', '请输入标题'], ['description', '请输入问题描述'],
  ['businessType', '请输入业务类型'], ['impactScope', '请输入影响范围']
].map(([field, message]) => [field, [{ required: true, whitespace: true, message, trigger: 'blur' }]]));

async function submit() {
  if (blocked.value || created.value) return;
  // Keep a detached snapshot until an uncertain submission has been resolved.
  busy.value = true;
  try {
    if (!pending.value) {
      if (!await form.value?.validate().catch(() => false)) return;
      pending.value = { body: { ...model }, key: crypto.randomUUID() };
    }
    error.value = undefined;
    created.value = await request<CreatedTicket>('/tickets', { method: 'POST', ...pending.value });
    pending.value = undefined;
  } catch (value) {
    showError(value);
    const failure = error.value!;
    if (failure.status >= 400 && failure.status < 500 && !['REQUEST_IN_PROGRESS', 'RATE_LIMITED'].includes(failure.code)) {
      pending.value = undefined;
    }
  } finally { busy.value = false; }
}

function another() {
  created.value = undefined;
  pending.value = undefined;
  error.value = undefined;
  Object.assign(model, { title: '', description: '', businessType: '', impactScope: '' });
}
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">内部服务</span><h1>提交工单</h1><p>描述你遇到的问题，方便 IT 团队了解情况。</p></div></div>
  <section class="ticket-card">
    <ElResult v-if="created" icon="success" title="工单提交成功">
      <template #sub-title><div class="ticket-receipt" aria-live="polite"><p>工单编号：<strong>{{ created.number }}</strong></p><p>工单 ID：{{ created.id }}</p></div></template>
      <template #extra><RouterLink :to="`/tickets/${created.id}`"><ElButton type="primary">查看工单</ElButton></RouterLink><ElButton class="another-ticket" @click="another">继续提单</ElButton></template>
    </ElResult>
    <template v-else>
      <ErrorNotice :error="error" />
      <ElAlert v-if="pending && error" class="ticket-retry" title="提交结果尚未确认，请重试确认。重试会保留本次填写内容，避免重复建单。" type="info" :closable="false" />
      <ElForm ref="form" :model="model" :rules="rules" label-position="top" :disabled="busy || !!pending" @submit.prevent="submit">
        <ElFormItem label="标题" prop="title"><ElInput v-model="model.title" placeholder="简要描述遇到的问题" /></ElFormItem>
        <ElFormItem label="问题描述" prop="description"><ElInput v-model="model.description" type="textarea" :rows="7" placeholder="请说明问题现象、发生时间及已尝试的操作" /></ElFormItem>
        <div class="ticket-fields"><ElFormItem label="业务类型" prop="businessType"><ElInput v-model="model.businessType" placeholder="填写相关业务或服务" /></ElFormItem><ElFormItem label="影响范围" prop="impactScope"><ElInput v-model="model.impactScope" placeholder="说明受影响的人员或业务范围" /></ElFormItem></div>
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
@media (max-width: 700px) { .ticket-fields { grid-template-columns: 1fr; gap: 0; } .ticket-card { padding: 20px; } }
</style>
