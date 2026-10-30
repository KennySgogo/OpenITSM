<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import ErrorNotice from '../components/ErrorNotice.vue';
import { request } from '../api/HttpClient';
import { restoreSession, clearSession } from '../stores/SessionStore';
import { useFeedback } from '../composables/useFeedback';
import { passwordIssues } from '../utils/Policy';
interface Profile { id: string; name: string; skillsDescription: string; version: number }
const router = useRouter();
const { error, busy, blocked, showError } = useFeedback();
const profile = ref<Profile>();
const form = reactive({ name: '', skillsDescription: '' });
const passwords = reactive({ oldPassword: '', password: '', confirmPassword: '' });
const message = ref('');
async function load() {
  const value = await request<Profile>('/profile');
  profile.value = value;
  form.name = value.name; form.skillsDescription = value.skillsDescription;
}
async function refresh() {
  busy.value = true; error.value = undefined;
  try { await load(); }
  catch (value) { showError(value); }
  finally { busy.value = false; }
}
async function save() {
  if (blocked.value || !profile.value) return;
  busy.value = true; error.value = undefined; message.value = '';
  try {
    await request('/profile', { method: 'PATCH', body: { ...form } });
    await Promise.all([load(), restoreSession()]); message.value = '资料已保存';
  } catch (value) { showError(value); }
  finally { busy.value = false; }
}
async function changePassword() {
  if (blocked.value || passwordIssues(passwords.password, passwords.confirmPassword).length) return;
  busy.value = true; error.value = undefined;
  try {
    await request('/auth/password-changes', { method: 'POST', body: { ...passwords } });
    passwords.oldPassword = ''; passwords.password = ''; passwords.confirmPassword = '';
    clearSession(); await router.replace('/login');
  } catch (value) { showError(value); }
  finally { busy.value = false; }
}
onMounted(refresh);
</script>
<template>
  <div class="page-heading"><div><h1>个人资料</h1><p>维护姓名、技能描述和登录密码</p></div></div>
  <ErrorNotice :error="error" />
  <ElAlert v-if="message" :title="message" type="success" :closable="false" />
  <ElCard v-if="profile" style="max-width:720px;margin-bottom:24px">
    <ElForm label-position="top" @submit.prevent="save">
      <ElFormItem label="姓名"><ElInput v-model="form.name" :disabled="busy" maxlength="80" /></ElFormItem>
      <ElFormItem label="技能描述"><ElInput v-model="form.skillsDescription" :disabled="busy" type="textarea" :rows="5" /></ElFormItem>
      <ElButton type="primary" native-type="submit" :loading="busy" :disabled="blocked">{{ '保存资料' }}</ElButton>
      <ElButton :disabled="busy" @click="refresh">重新读取</ElButton>
    </ElForm>
  </ElCard>
  <ElCard style="max-width:720px">
    <h2>修改密码</h2><p>成功后所有设备会话失效，请重新登录。结果不确定时先尝试使用新密码登录。</p>
    <ElForm label-position="top" @submit.prevent="changePassword">
      <ElFormItem label="旧密码"><ElInput v-model="passwords.oldPassword" type="password" show-password autocomplete="current-password" /></ElFormItem>
      <ElFormItem label="新密码"><ElInput v-model="passwords.password" type="password" show-password autocomplete="new-password" /></ElFormItem>
      <ElFormItem label="确认新密码"><ElInput v-model="passwords.confirmPassword" type="password" show-password autocomplete="new-password" /></ElFormItem>
      <p>至少 6 个字符</p>
      <ElButton native-type="submit" type="primary" :disabled="blocked || !passwords.oldPassword || !!passwordIssues(passwords.password, passwords.confirmPassword).length" :loading="busy">确认修改并退出登录</ElButton>
    </ElForm>
  </ElCard>
</template>
