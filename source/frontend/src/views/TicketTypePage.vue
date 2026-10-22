<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { request, type RequestOptions } from '../api/HttpClient';
import ErrorNotice from '../components/ErrorNotice.vue';
import { useFeedback } from '../composables/useFeedback';
interface Type { id: string; name: string; description?: string; canManage: boolean }
const items = ref<Type[]>([]);
const { error, busy, blocked, showError } = useFeedback();
const dialog = ref(false); const name = ref(''); const editing = ref<Type>();
const description = ref('');
const descriptionTooLong = () => [...description.value.trim()].length > 500;
async function load() { items.value = (await request<{ items: Type[] }>('/ticket-types?view=MANAGE')).items; }
async function refresh() {
  busy.value = true; error.value = undefined;
  try { await load(); } catch (value) { showError(value); } finally { busy.value = false; }
}
function open(item?: Type) { editing.value = item; name.value = item?.name ?? ''; description.value = item?.description ?? ''; dialog.value = true; }
async function send(path: string, options: RequestOptions) {
  if (blocked.value) return;
  busy.value = true; error.value = undefined;
  try { await request(path, options); dialog.value = false; await load(); }
  catch (value) { showError(value); }
  finally { busy.value = false; }
}
async function save() {
  if (!name.value.trim() || descriptionTooLong()) return;
  await send(`/ticket-types${editing.value ? '/' + editing.value.id : ''}`, {
    method: editing.value ? 'PATCH' : 'POST', body: { name: name.value, description: description.value }
  });
}
async function remove(item: Type) {
  await send(`/ticket-types/${item.id}`, { method: 'DELETE' });
}
onMounted(refresh);
</script>
<template>
  <div class="page-heading"><div><h1>业务类型</h1><p>移除后停止新提单使用，历史关联保留；改名同步用于历史展示。</p></div><ElButton type="primary" :disabled="blocked" @click="open()">创建类型</ElButton></div>
  <ErrorNotice :error="error" />
  <ElButton :disabled="blocked" @click="refresh">刷新列表</ElButton>
  <ElTable :data="items" v-loading="busy"><ElTableColumn prop="name" label="名称" /><ElTableColumn label="业务说明"><template #default="{ row }"><span class="type-description">{{ row.description || '暂无说明' }}</span></template></ElTableColumn><ElTableColumn label="操作"><template #default="{ row }"><ElButton v-if="row.canManage" :disabled="blocked" @click="open(row)">编辑</ElButton><ElPopconfirm v-if="row.canManage" title="从新提单选项中移除此类型？" @confirm="remove(row)"><template #reference><ElButton :disabled="blocked">移除</ElButton></template></ElPopconfirm></template></ElTableColumn></ElTable>
  <ElDialog v-model="dialog" :title="editing ? '编辑业务类型' : '创建类型'" :close-on-click-modal="false" :show-close="!busy">
    <ElForm label-position="top">
      <ElFormItem label="类型名称" required><ElInput v-model="name" :disabled="busy" placeholder="类型名称" /></ElFormItem>
      <ElFormItem label="业务说明（选填）" :error="descriptionTooLong() ? '说明最多 500 字' : undefined"><ElInput v-model="description" type="textarea" :rows="4" :disabled="busy" placeholder="说明适用范围、常见问题，以及与相似类型的区别。最多 500 字。" /><p>说明对全团队可见，并用于 AI 类型判断。</p></ElFormItem>
    </ElForm>
    <template #footer><ElButton :disabled="busy" @click="dialog = false">取消</ElButton><ElButton type="primary" :disabled="blocked || !name.trim() || descriptionTooLong()" @click="save">保存</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.type-description { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
