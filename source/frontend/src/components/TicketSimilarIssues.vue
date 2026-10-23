<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import ErrorNotice from './ErrorNotice.vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import type { Ticket } from '../types/ApiTypes';
const props = defineProps<{ ticket: Ticket; refreshing: boolean }>();
const { error, busy, blocked, showError } = useFeedback();
interface Source { sourceType: 'TICKET' | 'KNOWLEDGE'; sourceId: string; number: string | null; title: string; excerpt: string; reason: string }
const items = ref<Source[]>();
let sequence = 0;
function invalidate() { sequence++; items.value = undefined; error.value = undefined; busy.value = false; }
watch(() => [props.ticket.id, props.ticket.version, props.refreshing], invalidate, { flush: 'sync' });
onUnmounted(invalidate);
async function search() {
  if (blocked.value || props.refreshing) return;
  const current = ++sequence;
  busy.value = true; error.value = undefined; items.value = undefined;
  try {
    const result = await request<{ items: Source[] }>(`/tickets/${props.ticket.id}/ai/similar-issues`, { method: 'POST' });
    if (current === sequence) items.value = result.items;
  } catch (value) { if (current === sequence) showError(value); }
  finally { if (current === sequence) busy.value = false; }
}
</script>

<template>
  <section class="similar-issues" aria-label="相似问题与知识">
    <h3>相似问题与知识</h3>
    <p>从已解决工单和已发布知识中查找参考，打开原文核对后再采用。</p>
    <ElButton :loading="busy" :disabled="blocked || refreshing" @click="search">查找相似问题</ElButton>
    <RouterLink to="/tickets?view=ALL">人工搜索工单</RouterLink>
    <RouterLink to="/knowledge">搜索知识库</RouterLink>
    <ErrorNotice :error="error" />
    <p v-if="items && !items.length" role="status">暂无相似来源，可继续人工搜索或直接处理。</p>
    <article v-for="source in items" :key="`${source.sourceType}:${source.sourceId}`">
      <RouterLink :to="source.sourceType === 'TICKET' ? `/tickets/${source.sourceId}` : `/knowledge/${source.sourceId}`">{{ source.sourceType === 'TICKET' ? source.number : `知识 ${source.sourceId}` }} · {{ source.title }}</RouterLink>
      <p>{{ source.excerpt }}</p><p>相关依据：{{ source.reason }}</p>
    </article>
  </section>
</template>

<style scoped>
.similar-issues { margin-top: 24px; padding: 22px 26px; background: #fff; border: 1px solid #e3e9f2; border-radius: 9px; }
h3 { margin: 0 0 12px; font-size: 15px; }
p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; font-size: 13px; }
a { margin-left: 12px; overflow-wrap: anywhere; }
article { border-top: 1px solid #e3e9f2; margin-top: 16px; padding-top: 16px; }
article a { margin-left: 0; }
</style>
