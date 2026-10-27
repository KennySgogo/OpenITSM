<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import ErrorNotice from './ErrorNotice.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import type { Ticket } from '../types/ApiTypes';

const props = defineProps<{ ticket: Ticket; disabled: boolean; hasReplyText: boolean }>();
const emit = defineEmits<{ adopt: [content: string, version: number] }>();
interface Draft { content: string; basedOnVersion: number; hasReferenceSources: boolean; sources: Array<{ sourceType: 'TICKET' | 'KNOWLEDGE'; sourceId: string; number: string | null; title: string }> }
const draft = ref<Draft>();
const { error, busy, blocked, showError } = useFeedback();
const stale = ref(false);
let sequence = 0;
function invalidate() { sequence++; busy.value = false; stale.value = !!draft.value; }
watch(() => [props.ticket.id, props.ticket.version, props.disabled], invalidate, { flush: 'sync' });
onUnmounted(invalidate);
const canAdopt = computed(() => !!draft.value?.content.trim() && !stale.value && !props.disabled && !props.hasReplyText && !busy.value);
async function generate() {
  if (blocked.value || props.disabled) return;
  const current = ++sequence;
  busy.value = true; error.value = undefined;
  try {
    const result = await request<Draft>(`/tickets/${props.ticket.id}/ai/reply-drafts`, { method: 'POST' });
    if (sequence === current) { draft.value = result; stale.value = result.basedOnVersion !== props.ticket.version; }
  } catch (value) { if (sequence === current) showError(value); }
  finally { if (sequence === current) busy.value = false; }
}
function adopt() { if (canAdopt.value) emit('adopt', draft.value!.content, draft.value!.basedOnVersion); }
</script>

<template>
  <section class="reply-draft" aria-label="AI 回复草稿">
    <h3>AI 回复草稿</h3>
    <p>草稿仅保留在当前页面，请核对和修改后填入公开回复，再手动发送。</p>
    <ElButton :loading="busy" :disabled="blocked || disabled" @click="generate">生成回复草稿</ElButton>
    <ErrorNotice :error="error" />
    <template v-if="draft">
      <p v-if="stale" role="status">工单或页面状态已变化，请重新核对并生成草稿。原文字仍可手动复制。</p>
      <ElInput v-model="draft.content" aria-label="AI 草稿内容" type="textarea" :rows="7" :maxlength="2000" :disabled="busy" />
      <p v-if="!draft.hasReferenceSources">无参考来源：以下内容为一般建议或补充询问，请自行核实。</p>
      <p v-else>参考来源（请打开原文核对）：</p>
      <ul><li v-for="source in draft.sources" :key="`${source.sourceType}:${source.sourceId}`"><RouterLink :to="source.sourceType === 'TICKET' ? `/tickets/${source.sourceId}` : `/knowledge/${source.sourceId}`">{{ source.number ?? '知识' }} · {{ source.title }}</RouterLink></li></ul>
      <p v-if="hasReplyText">回复框已有内容，保留现有文字；清空后可填入此草稿。</p>
      <ElButton :disabled="!canAdopt" @click="adopt">填入公开回复</ElButton>
    </template>
  </section>
</template>

<style scoped>
.reply-draft { padding: 18px; margin-bottom: 22px; background: #f4f8ff; border: 1px solid #e3e9f2; border-radius: 8px; }
p, li { font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.el-textarea { margin-top: 14px; }
</style>
