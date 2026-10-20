<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import ErrorNotice from '../components/ErrorNotice.vue';
import { ApiError, request } from '../api/HttpClient';
import type { RequestOptions } from '../api/HttpClient';
import { roleLabels, statusLabels } from '../types/ApiTypes';
import type { MemberPage, MemberAction, User, Role, MemberStatus } from '../types/ApiTypes';
import { useFeedback } from '../composables/useFeedback';
import { isLocked } from '../utils/Policy';
import { sessionState, restoreSession } from '../stores/SessionStore';

type Operation = 'invite' | 'edit' | 'disable' | 'unlock' | 'invitation-resend';
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const listError = ref<ApiError>();
const loading = ref(false);
const page = ref(1);
const query = reactive({ q: '', role: '' as Role | '', status: '' as MemberStatus | '' });
const members = ref<MemberPage>({ items: [], page: 1, pageSize: 20, total: 0 });
const now = ref(Date.now());
const clock = window.setInterval(() => { now.value = Date.now(); }, 1000);
onUnmounted(() => window.clearInterval(clock));
let listSequence = 0;
async function load() {
  const sequence = ++listSequence;
  loading.value = true;
  listError.value = undefined;
  const params = new URLSearchParams({ page: String(page.value) });
  if (query.q.trim()) params.set('q', query.q.trim());
  if (query.role) params.set('role', query.role);
  if (query.status) params.set('status', query.status);
  try {
    const value = await request<MemberPage>(`/users?${params}`);
    if (sequence !== listSequence) return;
    if (!value.items.length && page.value > 1) { page.value--; void load(); return; }
    members.value = value;
  } catch (value) { if (sequence === listSequence) listError.value = value as ApiError; }
  finally { if (sequence === listSequence) loading.value = false; }
}
function filter() { page.value = 1; void load(); }
function resetFilters() { query.q = ''; query.role = ''; query.status = ''; filter(); }
onMounted(load);
const dialog = ref(false);
const operation = ref<Operation>('invite');
const target = ref<User>();
const form = ref<FormInstance>();
const model = reactive({ name: '', email: '', role: 'EMPLOYEE' as Role, reason: '' });
const pending = ref<{ path: string; options: RequestOptions }>();
const mayRetry = computed(() => !!pending.value && !!error.value && (error.value.retryable || error.value.code === 'REQUEST_IN_PROGRESS'));
const titles: Record<Operation, string> = { invite: '邀请新成员', edit: '编辑成员', disable: '停用成员', unlock: '解除登录锁定', 'invitation-resend': '重新发送邀请' };
const rules: FormRules = {
  name: [{ required: true, whitespace: true, message: '请输入姓名', trigger: 'blur' }, { max: 80, message: '姓名最多 80 个字符', trigger: 'blur' }],
  email: [{ required: true, message: '请输入邮箱', trigger: 'blur' }, { type: 'email', message: '请输入有效邮箱', trigger: 'blur' }, { max: 254, message: '邮箱最多 254 个字符', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  reason: [{ validator: (_rule, value: string, callback) => callback([...value.trim()].length >= 5 && [...value.trim()].length <= 500 ? undefined : new Error('停用原因须为 5～500 个字符')), trigger: ['blur', 'change'] }]
};
function open(action: Operation, member?: User) {
  operation.value = action;
  target.value = member ? { ...member } : undefined;
  model.name = member?.name || '';
  model.email = member?.email || '';
  model.role = member?.role || 'EMPLOYEE';
  model.reason = '';
  error.value = undefined;
  pending.value = undefined;
  dialog.value = true;
}
function close() { if (!busy.value) { dialog.value = false; pending.value = undefined; } }
async function submit() {
  if (blocked.value) return;
  if (!pending.value) {
    if (['invite', 'edit', 'disable'].includes(operation.value) && !await form.value?.validate().catch(() => false)) return;
    const member = target.value;
    let body: unknown;
    if (operation.value === 'invite') body = { name: model.name.trim(), email: model.email.trim().toLowerCase(), role: model.role };
    if (operation.value === 'edit') body = { name: model.name.trim(), role: model.role };
    if (operation.value === 'disable') body = { reason: model.reason.trim() };
    const suffix = operation.value === 'invite' || operation.value === 'edit' ? '' : `/${operation.value}`;
    pending.value = {
      path: operation.value === 'invite' ? '/users' : `/users/${member!.id}${suffix}`,
      options: { method: operation.value === 'edit' ? 'PATCH' : 'POST', body, key: crypto.randomUUID(), ...(member ? { version: member.version } : {}) }
    };
  }
  busy.value = true;
  error.value = undefined;
  try {
    const value = await request<MemberAction>(pending.value.path, pending.value.options);
    ElMessage.success(value.replayed ? '此操作此前已受理，已同步最新列表' : operation.value === 'invite' || operation.value === 'invitation-resend' ? '邀请已受理，成员须通过邮件链接激活后登录' : '成员操作已完成');
    dialog.value = false;
    pending.value = undefined;
    await load();
    if (target.value?.id === sessionState.session?.user.id) await restoreSession();
  } catch (value) {
    showError(value);
    const failure = error.value!;
    if (['VERSION_CONFLICT', 'RESOURCE_NOT_FOUND', 'INVALID_STATE', 'PRECONDITION_REQUIRED', 'IDEMPOTENCY_CONFLICT'].includes(failure.code)) {
      dialog.value = false;
      pending.value = undefined;
      await load();
      // Retain the server message on the page; never silently resubmit a changed version.
    } else if (!failure.retryable && failure.code !== 'REQUEST_IN_PROGRESS') pending.value = undefined;
  } finally { busy.value = false; }
}
</script>
<template>
  <div class="page-heading"><div><span class="eyebrow">团队管理</span><h1>成员管理</h1><p>管理团队成员、角色与账号访问权限。</p></div><ElButton type="primary" :disabled="blocked" @click="open('invite')"><span class="button-plus">＋</span>邀请成员</ElButton></div>
  <ErrorNotice v-if="!dialog" :error="error" />
  <section class="members-card">
    <ElTabs v-model="query.status" class="member-tabs" @tab-change="filter"><ElTabPane label="全部成员" name=""/><ElTabPane label="在职" name="ACTIVE"/><ElTabPane label="待激活" name="INVITED"/><ElTabPane label="已停用" name="DISABLED"/></ElTabs>
    <div class="member-filters"><ElInput v-model="query.q" class="member-search" placeholder="搜索姓名或邮箱" :maxlength="100" clearable aria-label="搜索成员" @keyup.enter="filter" @clear="filter"><template #prefix>⌕</template></ElInput><ElSelect v-model="query.role" placeholder="全部角色" clearable aria-label="角色筛选" @change="filter"><ElOption v-for="(label, role) in roleLabels" :key="role" :label="label" :value="role"/></ElSelect><ElButton :disabled="loading || blocked" @click="filter">搜索</ElButton><ElButton :disabled="loading || blocked" @click="resetFilters">重置</ElButton><span class="filter-total">共 {{ members.total }} 位成员</span></div>
    <ErrorNotice :error="listError" />
    <div v-if="listError" class="list-retry"><ElButton :disabled="blocked" @click="load">{{ waitSeconds ? `${waitSeconds} 秒后重试` : '重新加载列表' }}</ElButton></div>
    <ElTable v-loading="loading" :data="listError ? [] : members.items" class="member-table" :row-key="(row: User) => row.id" :empty-text="listError ? '列表加载失败' : query.q || query.role || query.status ? '没有匹配的成员，试试调整筛选条件' : '暂无成员，可邀请成员加入团队'">
      <ElTableColumn label="成员" min-width="210"><template #default="{ row }"><div class="member-name"><ElAvatar :size="32" :class="`avatar-${row.role.toLowerCase()}`">{{ row.name.slice(0, 1) }}</ElAvatar><strong>{{ row.name }}</strong><span v-if="row.id === sessionState.session?.user.id" class="self-label">你</span></div></template></ElTableColumn>
      <ElTableColumn prop="email" label="邮箱地址" min-width="230" show-overflow-tooltip/>
      <ElTableColumn label="角色" width="135"><template #default="{ row }"><ElTag class="role-tag" :class="`role-${row.role.toLowerCase()}`" effect="light">{{ roleLabels[row.role as Role] }}</ElTag></template></ElTableColumn>
      <ElTableColumn label="账号状态" width="150"><template #default="{ row }"><ElTag :type="row.status === 'ACTIVE' ? 'success' : row.status === 'INVITED' ? 'primary' : 'info'" effect="light"><span class="status-dot"></span>{{ statusLabels[row.status as MemberStatus] }}</ElTag><span v-if="isLocked(row.lockedUntil, now)" class="lock-label">已锁定</span></template></ElTableColumn>
      <ElTableColumn label="操作" min-width="205" align="right"><template #default="{ row }"><ElButton link type="primary" :disabled="blocked" @click="open('edit', row)">编辑</ElButton><ElDropdown trigger="click" :disabled="blocked" @command="(action: Operation) => open(action, row)"><ElButton text :disabled="blocked" :aria-label="`${row.name}的更多操作`">•••</ElButton><template #dropdown><ElDropdownMenu><ElDropdownItem v-if="row.status === 'INVITED'" command="invitation-resend">重发邀请</ElDropdownItem><ElDropdownItem v-if="isLocked(row.lockedUntil, now)" command="unlock">解除锁定</ElDropdownItem><ElDropdownItem v-if="row.status === 'ACTIVE'" command="disable" class="danger-text">停用成员</ElDropdownItem><ElDropdownItem v-if="row.status === 'DISABLED' && !isLocked(row.lockedUntil, now)" disabled>无其他操作</ElDropdownItem></ElDropdownMenu></template></ElDropdown></template></ElTableColumn>
    </ElTable>
    <div class="table-footer"><span>{{ members.total ? `第 ${(members.page - 1) * 20 + 1}–${Math.min(members.page * 20, members.total)} 位，共 ${members.total} 位成员` : '共 0 位成员' }}</span><ElPagination v-model:current-page="page" :page-size="20" :total="members.total" layout="prev, pager, next" background :disabled="loading || blocked" @current-change="load"/></div>
  </section>
  <p class="members-note">成员角色由管理员统一分配。账号停用或角色变更后，该成员的现有会话将立即失效。</p>
  <ElDialog v-model="dialog" :title="titles[operation]" width="480px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="(done: () => void) => { if (!busy) done(); }" destroy-on-close>
    <p class="dialog-description">{{ operation === 'invite' ? '邀请链接有效期 48 小时。成员设置密码后即可加入团队。' : `${target?.name} · ${target?.email}` }}</p>
    <ErrorNotice :error="error" />
    <ElAlert v-if="mayRetry" title="重试将复用本次请求的幂等键和原始内容。若需修改内容，请关闭弹窗重新操作。" type="info" :closable="false"/>
    <ElForm ref="form" :model="model" :rules="rules" label-position="top" :disabled="busy || !!pending" @submit.prevent="submit">
      <template v-if="operation === 'invite' || operation === 'edit'"><ElFormItem label="姓名" prop="name"><ElInput v-model="model.name" placeholder="成员姓名" :maxlength="80"/></ElFormItem><ElFormItem v-if="operation === 'invite'" label="邮箱地址" prop="email"><ElInput v-model="model.email" placeholder="name@company.com" :maxlength="254"/></ElFormItem><ElFormItem label="成员角色" prop="role"><ElSelect v-model="model.role"><ElOption v-for="(label, role) in roleLabels" :key="role" :label="label" :value="role"/></ElSelect></ElFormItem><p class="role-description">员工：提交本人问题 · 工程师：处理工单 · 管理员：管理团队并处理本人负责工单</p></template>
      <ElFormItem v-if="operation === 'disable'" label="停用原因" prop="reason"><ElInput v-model="model.reason" type="textarea" :rows="4" :maxlength="500" show-word-limit placeholder="请填写 5～500 个字符的原因，用于审计记录"/></ElFormItem>
    </ElForm>
    <ElAlert v-if="operation === 'disable'" title="停用后将立即结束该成员的全部会话，并使邀请与重置链接失效。" type="warning" :closable="false"/>
    <ElAlert v-if="operation === 'invitation-resend'" title="重新发送后，之前的邀请链接将立即失效。" type="info" :closable="false"/>
    <ElAlert v-if="operation === 'unlock'" title="解除后成员可以重新尝试登录。" type="info" :closable="false"/>
    <template #footer><ElButton :disabled="busy" @click="close">取消</ElButton><ElButton :type="operation === 'disable' ? 'danger' : 'primary'" :loading="busy" :disabled="blocked" @click="submit">{{ waitSeconds ? `${waitSeconds} 秒后重试` : mayRetry ? '重试原操作' : operation === 'invite' ? '发送邀请' : operation === 'edit' ? '保存修改' : '确认操作' }}</ElButton></template>
  </ElDialog>
</template>
