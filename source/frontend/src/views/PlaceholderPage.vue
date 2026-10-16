<script setup lang="ts">
import { useRoute } from 'vue-router';
const route = useRoute();
</script>
<template>
  <div class="page-heading"><div><span class="eyebrow">工作空间</span><h1>{{ route.meta.title }}</h1></div><ElTag type="info" effect="plain">{{ route.meta.missing ? '页面未找到' : '未实现' }}</ElTag></div>
  <section class="placeholder-card"><div class="construction-symbol">▧</div><span class="eyebrow">{{ route.meta.missing ? '404' : '后续模块' }}</span><h2>{{ route.meta.missing ? '这里还没有页面' : '功能建设中' }}</h2><p>{{ route.meta.missing ? '请从左侧导航选择页面。' : '当前可使用登录、成员管理与提交工单，此页面尚未实现。' }}</p><p class="muted">{{ route.meta.missing ? '你仍可继续访问已实现的功能。' : '这里没有业务数据，也暂不支持业务操作。' }}</p></section>
</template>
