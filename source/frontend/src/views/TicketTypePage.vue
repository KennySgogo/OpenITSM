<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { request, type RequestOptions } from '../api/HttpClient';
import ErrorNotice from '../components/ErrorNotice.vue';
import { useFeedback } from '../composables/useFeedback';
interface Type { id: string; name: string; version: number; canManage: boolean }
const items = ref<Type[]>([]);
const { error, busy, blocked, showError } = useFeedback();
const dialog = ref(false); const name = ref(''); const editing = ref<Type>();
const pending = ref<{ path: string; options: RequestOptions }>();
async function load() { items.value = (await request<{ items: Type[] }>('/ticket-types?view=MANAGE')).items; }
async function refresh() {
  busy.value = true; error.value = undefined;
  try { await load(); } catch (value) { showError(value); } finally { busy.value = false; }
}
function open(item?: Type) { editing.value = item; name.value = item?.name ?? ''; dialog.value = true; }
async function send() {
  if (!pending.value || blocked.value) return;
  busy.value = true; error.value = undefined;
  try { await request(pending.value.path, pending.value.options); pending.value = undefined; dialog.value = false; await load(); }
  catch (value) {
    showError(value);
    if (error.value!.status >= 400 && error.value!.status < 500 && !['REQUEST_IN_PROGRESS', 'RATE_LIMITED'].includes(error.value!.code)) pending.value = undefined;
  } finally { busy.value = false; }
}
async function save() {
  if (!name.value.trim()) return;
  pending.value ??= { path: `/ticket-types${editing.value ? '/' + editing.value.id : ''}`,
    options: { method: editing.value ? 'PATCH' : 'POST', body: { name: name.value }, version: editing.value?.version, key: crypto.randomUUID() } };
  await send();
}
async function remove(item: Type) {
  pending.value = { path: `/ticket-types/${item.id}`, options: { method: 'DELETE', version: item.version, key: crypto.randomUUID() } };
  await send();
}
onMounted(refresh);
</script>
<template>
  <div class="page-heading"><div><h1>业务类型</h1><p>移除后停止新提单使用，历史关联保留；改名同步用于历史展示。</p></div><ElButton type="primary" :disabled="blocked || !!pending" @click="open()">创建类型</ElButton></div>
  <ErrorNotice :error="error" /><ElButton v-if="pending && error" :disabled="blocked" @click="send">重试原操作</ElButton>
  <ElButton :disabled="blocked || !!pending" @click="refresh">刷新列表</ElButton>
  <ElTable :data="items" v-loading="busy"><ElTableColumn prop="name" label="名称" /><ElTableColumn label="操作"><template #default="{ row }"><ElButton v-if="row.canManage" :disabled="blocked || !!pending" @click="open(row)">改名</ElButton><ElPopconfirm v-if="row.canManage" title="从新提单选项中移除此类型？" @confirm="remove(row)"><template #reference><ElButton :disabled="blocked || !!pending">移除</ElButton></template></ElPopconfirm></template></ElTableColumn></ElTable>
  <ElDialog v-model="dialog" :title="editing ? '修改类型名称' : '创建类型'" :close-on-click-modal="false" :show-close="!busy && !pending">
    <ElInput v-model="name" :disabled="busy || !!pending" placeholder="类型名称" />
    <template #footer><ElButton :disabled="busy || !!pending" @click="dialog = false">取消</ElButton><ElButton type="primary" :disabled="blocked || !!pending || !name.trim()" @click="save">保存</ElButton></template>
  </ElDialog>
</template>
