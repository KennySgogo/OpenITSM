import { reactive, ref } from 'vue';
import { request, clearHttpSession, setCsrfToken, ApiError } from '../api/HttpClient.ts';
import type { Session } from '../types/ApiTypes';

export const sessionState = reactive<{ session?: Session; restored: boolean }>({ restored: false });
export const sessionNotice = ref<ApiError>();
let restoring: Promise<void> | undefined;
let heartbeat: ReturnType<typeof setTimeout> | undefined;
let lastInteraction = 0;
let lastReported = 0;
let lastHeartbeat = 0;
let epoch = 0;
export const HEARTBEAT_MS = 60000;
export function stopHeartbeat() { if (heartbeat) clearTimeout(heartbeat); heartbeat = undefined; }
export function clearSession() {
  epoch++;
  stopHeartbeat();
  sessionState.session = undefined;
  sessionState.restored = true;
  clearHttpSession();
}
export function acceptSession(value: Session, userInitiated = false) {
  sessionNotice.value = undefined;
  epoch++;
  sessionState.session = value;
  sessionState.restored = true;
  setCsrfToken(value.csrfToken);
  if (userInitiated) {
    lastInteraction = Date.now();
    lastReported = 0;
  }
  scheduleHeartbeat();
}
export async function restoreSession() {
  if (!restoring) {
    const started = epoch;
    restoring = request<Session>('/auth/me').then(value => { if (started === epoch) acceptSession(value); }).finally(() => { restoring = undefined; });
  }
  return restoring;
}
function scheduleHeartbeat(delay = HEARTBEAT_MS) {
  stopHeartbeat();
  if (!sessionState.session || document.hidden) return;
  const started = epoch;
  heartbeat = setTimeout(async () => {
    const tickStarted = Date.now();
    let next = HEARTBEAT_MS;
    if (sessionState.session && !document.hidden && lastInteraction > lastReported) {
      const activityAt = lastInteraction;
      lastHeartbeat = Date.now();
      try { await request<void>('/auth/activity', { method: 'POST' }); lastReported = activityAt; }
      catch (value) {
        if (value instanceof ApiError && value.status === 401) return;
        if (value instanceof ApiError && value.status === 429) next = Math.max(HEARTBEAT_MS, value.retryAfter * 1000);
        else window.dispatchEvent(new CustomEvent('session-error', { detail: value }));
      }
    }
    if (epoch === started) scheduleHeartbeat(Math.max(0, next - (Date.now() - tickStarted)));
  }, delay);
}
export function installActivityTracking() {
  const interact = (event: Event) => { if (event.isTrusted) lastInteraction = Date.now(); };
  for (const event of ['pointerdown', 'keydown', 'scroll']) window.addEventListener(event, interact, { passive: true });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stopHeartbeat();
    else scheduleHeartbeat(Math.max(1000, HEARTBEAT_MS - (Date.now() - lastHeartbeat)));
  });
}
