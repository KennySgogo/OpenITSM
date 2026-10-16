<script setup lang="ts">
import type { ApiError } from '../api/HttpClient';
withDefaults(defineProps<{ error?: ApiError; showRequestId?: boolean }>(), {
  showRequestId: true
});
</script>
<template>
  <ElAlert v-if="error" class="error-notice" type="error" :closable="false" show-icon>
    <template #title>{{ error.message }}</template>
    <ul v-if="error.fieldErrors.length"><li v-for="field in error.fieldErrors" :key="field.field + field.message">{{ field.message }}</li></ul>
    <small v-if="showRequestId && error.requestId">请求编号：{{ error.requestId }}</small>
  </ElAlert>
</template>
