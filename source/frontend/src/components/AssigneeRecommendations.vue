<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { request } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';
import ErrorNotice from './ErrorNotice.vue';
const props = defineProps<{ endpoint: string; body?: { title: string; description: string; businessTypeId: string; impactScope: string }; invalidationKey: string; disabled?: boolean; canAdopt?: boolean }>();
const emit = defineEmits<{ adopt: [item: { assigneeId: string; name: string; reason: string }] }>();
const { error, busy, blocked, showError } = useFeedback();
const items = ref<Array<{ assigneeId: string; name: string; reason: string }>>();
const notice = ref('');
let sequence = 0;
function invalidate(message = '') { sequence++; items.value = undefined; error.value = undefined; busy.value = false; notice.value = message; }
watch(() => [props.endpoint, props.body, props.invalidationKey, props.disabled], () => invalidate(items.value || busy.value ? '输入或选择已变化，请重新生成推荐。' : ''), { deep: true, flush: 'sync' });
onUnmounted(invalidate);
async function generate() {
  if (blocked.value || props.disabled) return;
  const current = ++sequence;
  busy.value = true; error.value = undefined; items.value = undefined; notice.value = '';
  try {
    const result = await request<{ items: typeof items.value }>(props.endpoint, { method: 'POST', ...(props.body ? { body: props.body } : {}) });
    if (current === sequence) items.value = result.items;
  } catch (value) { if (current === sequence) showError(value); }
  finally { if (current === sequence) busy.value = false; }
}
</script>

<template>
  <section class="recommendations" aria-label="AI 处理人推荐">
    <h3>AI 处理人推荐</h3>
    <p>结合同类型解决数量、当前负载和本人技能提供建议，由你确认；AI 不可用时可继续人工操作。</p>
    <ElButton :loading="busy" :disabled="blocked || disabled" @click="generate">推荐处理人</ElButton>
    <ErrorNotice :error="error" />
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="items && !items.length" role="status">暂无合适处理人，请人工选择。</p>
    <div v-for="item in items" :key="item.assigneeId" class="candidate">
      <strong>{{ item.name }}</strong><p>{{ item.reason }}</p>
      <ElButton v-if="canAdopt" :disabled="disabled" @click="emit('adopt', item); invalidate()">采用此处理人</ElButton>
    </div>
  </section>
</template>

<style scoped>
.recommendations { margin: 20px 0; padding: 18px; border: 1px solid #e3e9f2; border-radius: 6px; background: #f6f9fd; }
h3 { margin: 0; font-size: 15px; }
p { overflow-wrap: anywhere; line-height: 1.8; font-size: 13px; }
.candidate { margin-top: 12px; border-top: 1px solid #e3e9f2; padding-top: 12px; }
</style>
