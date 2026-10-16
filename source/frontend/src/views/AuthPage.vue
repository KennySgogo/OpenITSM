<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import BrandMark from '../components/BrandMark.vue';
import ErrorNotice from '../components/ErrorNotice.vue';
import { request, clearHttpSession } from '../api/HttpClient';
import { acceptSession, clearSession, sessionNotice } from '../stores/SessionStore';
import { useFeedback } from '../composables/useFeedback';
import { passwordIssues } from '../utils/Policy';
import type { Session } from '../types/ApiTypes';

const route = useRoute();
const router = useRouter();
const mode = computed(() => route.path.slice(1));
const login = computed(() => mode.value === 'login');
const forgot = computed(() => mode.value === 'forgot-password');
const activation = computed(() => mode.value === 'activate');
const title = computed(() => login.value ? '欢迎回来' : forgot.value ? '找回你的密码' : activation.value ? '激活你的账号' : '设置新密码');
const subtitle = computed(() => login.value ? '登录你的账号，开启有序的一天。' : forgot.value ? '输入账号邮箱，申请密码重置链接。' : activation.value ? '完成密码设置，加入团队服务工作台。' : '为你的账号设置一个安全的新密码。');
const { error, busy, blocked, waitSeconds, showError } = useFeedback();
const form = ref<FormInstance>();
const model = reactive({ email: '', password: '', confirmPassword: '' });
const success = ref('');
// Token is kept only in this page's memory, never persisted or sent in a referrer.
const token = typeof route.query.token === 'string' ? route.query.token : '';
const tokenMissing = computed(() => !login.value && !forgot.value && [...token].length !== 43);
const issues = computed(() => passwordIssues(model.password, model.confirmPassword));
const rules = computed<FormRules>(() => ({
  email: [{ required: true, message: '请输入邮箱', trigger: 'blur' }, { type: 'email', message: '请输入有效邮箱', trigger: 'blur' }, { max: 254, message: '邮箱最多 254 个字符', trigger: 'blur' }],
  password: [{ validator: (_rule, value, callback) => {
    if (login.value) callback(value && [...value].length <= 64 ? undefined : new Error('请输入不超过 64 个字符的密码'));
    else {
      const found = passwordIssues(value, value);
      callback(found.length ? new Error(found.join('；')) : undefined);
    }
  }, trigger: ['blur', 'change'] }],
  confirmPassword: [{ validator: (_rule, value, callback) => callback(value === model.password && value ? undefined : new Error('两次密码必须一致')), trigger: ['blur', 'change'] }]
}));
async function submit() {
  if (blocked.value || tokenMissing.value || success.value) return;
  if (!await form.value?.validate().catch(() => false)) return;
  busy.value = true;
  error.value = undefined;
  sessionNotice.value = undefined;
  try {
    if (login.value) {
      const returnTo = typeof route.query.returnTo === 'string' ? route.query.returnTo : undefined;
      const value = await request<Session>('/auth/login', { method: 'POST', body: { email: model.email.trim().toLowerCase(), password: model.password, ...(returnTo ? { returnTo } : {}) } });
      acceptSession(value, true);
      ElMessage.success(value.message);
      await router.replace(value.redirectPath);
    } else if (forgot.value) {
      const value = await request<{ message: string }>('/auth/password-reset-requests', { method: 'POST', body: { email: model.email.trim().toLowerCase() } });
      success.value = value.message;
    } else {
      const value = await request<{ message: string }>(activation.value ? '/auth/activations' : '/auth/password-resets', { method: 'POST', body: { token, password: model.password, confirmPassword: model.confirmPassword } });
      clearSession();
      clearHttpSession();
      ElMessage.success(value.message);
      await router.replace('/login');
    }
  } catch (value) { showError(value); }
  finally { busy.value = false; }
}
</script>
<template>
  <main class="auth-screen">
    <div class="auth-top"><span>内部 IT 服务 · 更清晰，更有序</span><span>OpenITSM / M01</span></div>
    <section class="auth-card" :class="{ 'auth-card-login': login }">
      <aside v-if="login" class="auth-art" aria-label="蓝色建筑几何插画">
        <div class="art-grid"></div><div class="building building-one"></div><div class="building building-two"></div><div class="building building-three"></div>
        <div class="art-copy"><span class="eyebrow">连接团队 · 连接服务</span><h2>让每一份支持<br>都有迹可循。</h2><p>从一个账号开始，<br>让团队协作回归清晰与有序。</p></div>
      </aside>
      <div class="auth-content">
        <BrandMark />
        <template v-if="!success">
          <div v-if="!login" class="auth-symbol">{{ forgot ? '✉' : activation ? '✓' : '⌑' }}</div>
          <div class="auth-heading"><h1>{{ title }}</h1><p>{{ subtitle }}</p></div>
          <ErrorNotice :error="error || sessionNotice" :show-request-id="!login" />
          <ElAlert v-if="tokenMissing" title="链接缺少有效令牌，请从邮件中的完整链接进入。" type="warning" :closable="false" show-icon />
          <ElForm ref="form" :model="model" :rules="rules" label-position="top" @submit.prevent="submit">
            <ElFormItem v-if="login || forgot" label="邮箱地址" prop="email"><ElInput v-model="model.email" placeholder="you@company.com" autocomplete="username" :maxlength="254" /></ElFormItem>
            <ElFormItem v-if="!forgot" :label="login ? '密码' : '新密码'" prop="password"><ElInput v-model="model.password" type="password" show-password :autocomplete="login ? 'current-password' : 'new-password'" placeholder="请输入密码" /></ElFormItem>
            <div v-if="!forgot && !login" class="password-rules"><span :class="{ passed: !issues.some(x => x.includes('字符')) }">12～64 个字符</span><span :class="{ passed: !issues.some(x => x.includes('字母')) }">至少一个字母</span><span :class="{ passed: !issues.some(x => x.includes('数字')) }">至少一个数字</span></div>
            <ElFormItem v-if="!forgot && !login" label="确认新密码" prop="confirmPassword"><ElInput v-model="model.confirmPassword" type="password" show-password autocomplete="new-password" placeholder="再次输入新密码" /></ElFormItem>
            <div v-if="login" class="auth-options"><span>仅限受邀成员登录</span><RouterLink to="/forgot-password">忘记密码？</RouterLink></div>
            <ElButton class="auth-submit" type="primary" native-type="submit" :loading="busy" :disabled="blocked || tokenMissing">{{ waitSeconds ? `${waitSeconds} 秒后重试` : login ? '登 录' : forgot ? '申请重置链接' : activation ? '激活账号' : '重置密码' }}</ElButton>
          </ElForm>
          <div v-if="error?.code === 'TOKEN_INVALID'" class="auth-help"><span v-if="activation">请联系管理员重新邀请，旧链接已不可用。</span><RouterLink v-else to="/forgot-password">重新申请重置密码 →</RouterLink></div>
        </template>
        <ElResult v-else icon="success" title="申请已受理" :sub-title="success" />
        <p v-if="login" class="auth-help">还没有账号？<span>请联系团队管理员邀请</span></p>
        <RouterLink v-else class="back-login" to="/login">← 返回登录</RouterLink>
        <p v-if="!login && !forgot" class="expiry-note">{{ activation ? '邀请链接有效期 48 小时' : '重置链接有效期 30 分钟' }} · 仅可使用一次</p>
      </div>
    </section>
    <footer class="auth-footer">OpenITSM <span>·</span> 为团队提供有序的 IT 支持</footer>
  </main>
</template>
