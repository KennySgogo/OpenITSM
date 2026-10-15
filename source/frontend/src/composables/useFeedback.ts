import { computed, onUnmounted, ref } from 'vue';
import { ApiError, getCooldownUntil } from '../api/HttpClient';

export function useFeedback() {
  const error = ref<ApiError>();
  const busy = ref(false);
  const now = ref(Date.now());
  const timer = window.setInterval(() => { now.value = Date.now(); }, 500);
  onUnmounted(() => window.clearInterval(timer));
  const waitSeconds = computed(() => Math.max(0, Math.ceil((getCooldownUntil() - now.value) / 1000)));
  const blocked = computed(() => busy.value || waitSeconds.value > 0);
  function showError(value: unknown) {
    error.value = value instanceof ApiError ? value : new ApiError(0, { code: 'CLIENT_ERROR', message: '操作未完成，请重试' });
  }
  return { error, busy, waitSeconds, blocked, showError };
}
