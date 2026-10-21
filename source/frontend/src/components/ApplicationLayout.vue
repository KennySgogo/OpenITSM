<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import BrandMark from './BrandMark.vue';
import ErrorNotice from './ErrorNotice.vue';
import { sessionState, restoreSession, clearSession } from '../stores/SessionStore';
import { request } from '../api/HttpClient';
import { roleLabels } from '../types/ApiTypes';
import { useFeedback } from '../composables/useFeedback';
import { canAccess } from '../utils/Policy';
const route = useRoute();
const router = useRouter();
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const user = computed(() => sessionState.session?.user);
const allowed = computed(() => !!user.value && canAccess(user.value.role, route.path, String(route.query.view || '')));
const navigation = computed(() => [
  { to: '/tickets/new', label: '提交工单', symbol: '＋', implemented: true },
  ...(user.value?.role === 'ADMIN' ? [{ to: '/dashboard', label: '服务看板', symbol: '▦', implemented: true }] : []),
  { to: '/tickets?view=MINE', label: '我的工单', symbol: '▤', implemented: true },
  ...(user.value?.role !== 'EMPLOYEE' ? [{ to: '/tickets?view=ASSIGNED', label: '我的待办', symbol: '☑', implemented: true }, { to: '/tickets?view=UNASSIGNED', label: '未分派队列', symbol: '▥', implemented: true }, { to: '/tickets?view=ALL', label: '全部工单', symbol: '▤', implemented: true }] : []),
  { to: '/knowledge', label: '知识库', symbol: '▧', implemented: true },
  ...(user.value?.role !== 'EMPLOYEE' ? [{ to: '/ticket-types', label: '业务类型', symbol: '◇', implemented: true }] : []),
  { to: '/profile', label: '个人资料', symbol: '○', implemented: true }
]);
function active(to: string) {
  if (to === '/knowledge') return route.path === '/knowledge' || route.path.startsWith('/knowledge/');
  if (to.startsWith('/tickets?view=')) return route.path === '/tickets' && (route.query.view ?? 'MINE') === to.slice('/tickets?view='.length);
  return route.fullPath === to || (!to.includes('?') && route.path === to);
}
async function recover() {
  busy.value = true;
  error.value = undefined;
  try { await restoreSession(); }
  catch (value) { showError(value); }
  finally { busy.value = false; }
}
async function logout() {
  if (blocked.value) return;
  busy.value = true;
  try { await request<void>('/auth/logout', { method: 'POST' }); clearSession(); await router.replace('/login'); }
  catch (value) { showError(value); }
  finally { busy.value = false; }
}
const activityError = (event: Event) => showError((event as CustomEvent).detail);
onMounted(() => { if (!sessionState.restored) void recover(); window.addEventListener('session-error', activityError); });
onUnmounted(() => window.removeEventListener('session-error', activityError));
</script>
<template>
  <div v-if="!user" class="session-recovery"><BrandMark /><p>正在恢复会话</p><ErrorNotice :error="error" /><ElButton :loading="busy" :disabled="blocked" @click="recover">{{ waitSeconds ? `${waitSeconds} 秒后重试` : '重新连接' }}</ElButton></div>
  <div v-else class="app-shell">
    <aside class="sidebar">
      <BrandMark /><div class="workspace-label">团队工作空间</div>
      <nav class="side-nav"><RouterLink v-for="item in navigation" :key="item.to" :to="item.to" :class="{ active: active(item.to) }"><span class="nav-symbol">{{ item.symbol }}</span>{{ item.label }}<span v-if="!('implemented' in item && item.implemented)" class="nav-planned">待建设</span></RouterLink></nav>
      <template v-if="user.role === 'ADMIN'"><div class="workspace-label admin-label">管理</div><nav class="side-nav"><RouterLink to="/admin/users" :class="{ active: route.path === '/admin/users' }"><span class="nav-symbol">♧</span>成员管理</RouterLink><RouterLink to="/settings" :class="{ active: route.path === '/settings' }"><span class="nav-symbol">⚙</span>团队配置</RouterLink></nav></template>
      <div class="sidebar-bottom"><div class="profile"><ElAvatar :size="36">{{ user.name.slice(0, 1) }}</ElAvatar><div><strong>{{ user.name }}</strong><span>{{ roleLabels[user.role] }}</span></div></div></div>
    </aside>
    <div class="app-main"><header class="topbar"><span class="breadcrumb">工作空间 <span>/</span> <strong>{{ route.meta.title }}</strong></span><div class="topbar-right"><span>{{ user.email }}</span><ElButton text :loading="busy" :disabled="blocked" @click="logout">{{ waitSeconds ? `${waitSeconds} 秒后重试` : '退出登录' }}</ElButton></div></header>
      <main class="page-body"><ErrorNotice :error="error" /><template v-if="allowed"><RouterView /></template><ElResult v-else icon="warning" title="无访问权限" sub-title="当前角色无权访问此页面。"><template #extra><ElButton type="primary" @click="router.replace(sessionState.session!.defaultPath)">返回默认页面</ElButton></template></ElResult></main>
    </div>
  </div>
</template>
