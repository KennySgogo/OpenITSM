<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import ErrorNotice from '../components/ErrorNotice.vue';
import { ApiError, request, type RequestOptions } from '../api/HttpClient';
import { useFeedback } from '../composables/useFeedback';

interface ConnectionStatus { status: string; testedVersion: number | null; occurredAt: string | null; current: boolean }
interface Settings {
  id: string; version: number; teamName: string; aiEnabled: boolean;
  smtp: { host: string; port: number; username: string; from: string; security: string; passwordConfigured: boolean };
  model: { baseUrl: string; model: string; apiKeyConfigured: boolean };
  lastTest: ConnectionStatus; lastCall: ConnectionStatus;
}
const { error, busy, blocked, showError } = useFeedback();
const saved = ref<Settings>();
const form = reactive({ teamName: '', aiEnabled: false,
  smtp: { host: '', port: 465, username: '', from: '', security: 'SSL', password: '', clearPassword: false },
  model: { baseUrl: '', model: '', apiKey: '', clearApiKey: false } });
const notice = ref('');
const pending = ref<RequestOptions>();
const testCooling = ref(false);
let cooldownTimer: ReturnType<typeof setTimeout> | undefined;
const frozen = computed(() => blocked.value || !!pending.value);
const labels: Record<string, string> = { NOT_TESTED: '未测试', NO_CALLS: '暂无调用', RUNNING: '测试中', SUCCESS: '连接成功',
  AI_TIMEOUT: '连接超时', AI_INVALID_RESPONSE: '返回格式无效', DEPENDENCY_UNAVAILABLE: '连接不可用' };
const diff = computed(() => {
  const value = saved.value;
  const body: Record<string, unknown> = {};
  if (!value) return body;
  if (form.teamName !== value.teamName) body.teamName = form.teamName;
  if (form.aiEnabled !== value.aiEnabled) body.aiEnabled = form.aiEnabled;
  const smtp: Record<string, unknown> = {};
  for (const key of ['host', 'port', 'username', 'from', 'security'] as const) if (form.smtp[key] !== value.smtp[key]) smtp[key] = form.smtp[key];
  if (form.smtp.clearPassword) smtp.clearPassword = true;
  else if (form.smtp.password) smtp.password = form.smtp.password;
  if (Object.keys(smtp).length) body.smtp = smtp;
  const model: Record<string, unknown> = {};
  for (const key of ['baseUrl', 'model'] as const) if (form.model[key] !== value.model[key]) model[key] = form.model[key];
  if (form.model.clearApiKey) model.clearApiKey = true;
  else if (form.model.apiKey) model.apiKey = form.model.apiKey;
  if (Object.keys(model).length) body.model = model;
  return body;
});
async function read(reset: boolean) {
  const value = await request<Settings>('/settings');
  if (!reset && saved.value && value.version !== saved.value.version) {
    saved.value.lastTest = value.lastTest; saved.value.lastCall = value.lastCall;
    notice.value = '服务器配置已变化，请重新读取后核对。当前表单不会覆盖新的配置。';
    return;
  }
  saved.value = value;
  if (reset) {
    form.teamName = value.teamName; form.aiEnabled = value.aiEnabled;
    Object.assign(form.smtp, value.smtp, { password: '', clearPassword: false });
    Object.assign(form.model, value.model, { apiKey: '', clearApiKey: false });
  }
}
async function refresh() {
  if (busy.value) return;
  busy.value = true; error.value = undefined;
  try { await read(true); pending.value = undefined; notice.value = ''; }
  catch (value) { showError(value); }
  finally { busy.value = false; }
}
async function save() {
  if (blocked.value || !saved.value) return;
  busy.value = true; error.value = undefined; notice.value = '';
  pending.value ??= { method: 'PATCH', body: JSON.parse(JSON.stringify(diff.value)), version: saved.value.version, key: crypto.randomUUID() };
  try {
    await request('/settings', pending.value);
    pending.value = undefined;
    form.smtp.password = ''; form.model.apiKey = '';
    notice.value = '配置已保存。连接测试和 AI 开关需分别操作。';
    await read(true);
  } catch (value) {
    showError(value);
    if (error.value!.status >= 400 && error.value!.status < 500 && error.value!.code !== 'REQUEST_IN_PROGRESS') pending.value = undefined;
  } finally { busy.value = false; }
}
async function test() {
  if (blocked.value || pending.value || !saved.value) return;
  busy.value = true; error.value = undefined; notice.value = '';
  try {
    const result = await request<{ status: string; testedVersion: number; current: boolean }>('/settings/model-connection-tests', { method: 'POST' });
    notice.value = `已保存版本 ${result.testedVersion} 连接成功。${result.current ? '' : '配置已变化，请重新测试当前版本。'}`;
  } catch (value) {
    showError(value);
    if (value instanceof ApiError && value.status === 429) {
      testCooling.value = true;
      if (cooldownTimer) clearTimeout(cooldownTimer);
      cooldownTimer = setTimeout(() => { testCooling.value = false; }, value.retryAfter * 1000);
    }
  }
  finally {
    try { await read(false); } catch (value) { if (!error.value) showError(value); }
    busy.value = false;
  }
}
function when(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN') : ''; }
onMounted(refresh);
onUnmounted(() => { if (cooldownTimer) clearTimeout(cooldownTimer); });
</script>

<template>
  <section class="settings-page">
    <div class="page-heading"><div><h1>团队配置</h1><p>管理团队、邮件与模型连接。凭据仅可替换或显式清除。</p></div>
      <ElButton :disabled="busy" @click="refresh">重新读取（放弃未保存修改）</ElButton></div>
    <ErrorNotice :error="error" />
    <ElAlert v-if="notice" :title="notice" type="success" :closable="false" show-icon />
    <ElAlert v-if="pending" title="保存结果尚未确认，表单已锁定。请使用原请求重试，或重新读取服务器配置。" type="warning" :closable="false" />
    <template v-if="saved">
      <ElForm label-position="top" :disabled="frozen" @submit.prevent="save">
        <ElCard shadow="never"><h2>团队</h2><ElFormItem label="团队名称"><ElInput v-model="form.teamName" maxlength="80" /></ElFormItem></ElCard>
        <ElCard shadow="never"><h2>邮件连接</h2>
          <p>保存连接不会开启邮件投递。投递总开关与工单邮件开关由服务端维护，启用前须核对积压任务。</p>
          <div class="settings-grid">
            <ElFormItem label="SMTP 服务器"><ElInput v-model="form.smtp.host" maxlength="253" /></ElFormItem>
            <ElFormItem label="端口"><ElInputNumber v-model="form.smtp.port" :min="1" :max="65535" /></ElFormItem>
            <ElFormItem label="用户名"><ElInput v-model="form.smtp.username" autocomplete="off" maxlength="320" /></ElFormItem>
            <ElFormItem label="发件邮箱"><ElInput v-model="form.smtp.from" maxlength="320" /></ElFormItem>
            <ElFormItem label="连接安全"><ElSelect v-model="form.smtp.security"><ElOption label="SSL" value="SSL" /><ElOption label="STARTTLS" value="STARTTLS" /><ElOption label="NONE（仅本机测试）" value="NONE" /></ElSelect></ElFormItem>
            <ElFormItem :label="`替换 SMTP 密码（${saved.smtp.passwordConfigured ? '已配置' : '未配置'}）`"><ElInput v-model="form.smtp.password" type="password" autocomplete="new-password" :disabled="form.smtp.clearPassword" maxlength="512" placeholder="留空保留原凭据" /></ElFormItem>
          </div>
          <ElCheckbox v-model="form.smtp.clearPassword" @change="form.smtp.password = ''">显式清除已保存的 SMTP 密码</ElCheckbox>
        </ElCard>
        <ElCard shadow="never"><h2>模型连接</h2><p>配置一个兼容 Chat Completions 的模型连接。Base URL 包含 API 前缀，例如 https://模型服务地址/v1。</p>
          <div class="settings-grid">
            <ElFormItem label="Base URL"><ElInput v-model="form.model.baseUrl" maxlength="2048" /></ElFormItem>
            <ElFormItem label="模型名称"><ElInput v-model="form.model.model" maxlength="200" /></ElFormItem>
            <ElFormItem :label="`替换模型密钥（${saved.model.apiKeyConfigured ? '已配置' : '未配置'}）`"><ElInput v-model="form.model.apiKey" type="password" autocomplete="new-password" :disabled="form.model.clearApiKey" maxlength="512" placeholder="留空保留原凭据" /></ElFormItem>
          </div>
          <ElCheckbox v-model="form.model.clearApiKey" @change="form.model.apiKey = ''">显式清除已保存的模型密钥</ElCheckbox>
          <ElFormItem label="AI 总开关"><ElSwitch v-model="form.aiEnabled" /><span class="switch-note">未配置完整连接时不能开启；清除密钥时请同时关闭。</span></ElFormItem>
        </ElCard>
      </ElForm>
      <div class="settings-actions"><ElButton type="primary" :disabled="blocked || (!pending && !Object.keys(diff).length)" :loading="busy" @click="save">{{ pending ? '重试原保存请求' : '保存配置' }}</ElButton><span>已保存版本 {{ saved.version }}</span></div>
      <ElCard shadow="never"><h2>连接与调用状态</h2>
        <p>测试只使用已保存的连接及固定文本，AI 关闭时也可测试。未保存修改不会参与测试；每位管理员每 1 秒最多一次。</p>
        <div class="status-line">模型测试：{{ labels[saved.lastTest.status] || saved.lastTest.status }} <span v-if="saved.lastTest.testedVersion !== null"> · 版本 {{ saved.lastTest.testedVersion }} · {{ when(saved.lastTest.occurredAt) }} {{ saved.lastTest.current ? '' : '（旧配置结果）' }}</span></div>
        <div class="status-line">业务调用：{{ labels[saved.lastCall.status] || saved.lastCall.status }}</div>
        <ElButton :disabled="blocked || testCooling || !!pending || Object.keys(diff).length > 0 || !saved.model.apiKeyConfigured || !saved.model.baseUrl || !saved.model.model || saved.version === 0" :loading="busy" @click="test">{{ testCooling ? '测试冷却中，请稍后重试' : '测试已保存连接' }}</ElButton>
        <p v-if="Object.keys(diff).length">请先保存或放弃表单修改，再测试已保存连接。</p>
      </ElCard>
    </template>
  </section>
</template>

<style scoped>
.settings-page { max-width: 1000px; }
.page-heading { display: flex; justify-content: space-between; align-items: center; gap: 20px; }
h1 { font-size: 24px; margin: 0 0 8px; } h2 { font-size: 18px; margin: 0 0 16px; }
p, .settings-actions span { color: #64748b; line-height: 1.7; }
.el-card, .el-alert { margin: 18px 0; }
.settings-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 24px; }
.settings-actions { display: flex; align-items: center; gap: 16px; }
.switch-note { margin-left: 12px; color: #64748b; }
.status-line { margin-bottom: 14px; }
@media (max-width: 700px) { .settings-grid { grid-template-columns: 1fr; } .page-heading { align-items: flex-start; flex-direction: column; } }
</style>
