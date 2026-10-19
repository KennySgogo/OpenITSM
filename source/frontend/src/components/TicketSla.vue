<script setup lang="ts">
import type { TicketSla } from '../types/ApiTypes';

defineProps<{ sla: TicketSla | null; compact?: boolean }>();
function time(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '尚未完成';
}
</script>

<template>
  <span v-if="!sla" class="sla-unknown">历史数据不足</span>
  <div v-else-if="compact" class="sla-tags">
    <ElTag :type="sla.responseOverdue ? 'danger' : sla.firstRespondedAt ? 'success' : 'info'" size="small">响应{{ sla.responseOverdue ? '超时' : sla.firstRespondedAt ? '达标' : '计时中' }}</ElTag>
    <ElTag :type="sla.resolutionOverdue ? 'danger' : sla.resolvedAt ? 'success' : 'info'" size="small">解决{{ sla.resolutionOverdue ? '超时' : sla.resolvedAt ? '达标' : '计时中' }}</ElTag>
  </div>
  <div v-else class="sla-detail">
    <ElDescriptions :column="1" border>
      <ElDescriptionsItem label="首次人工响应">
        <ElTag :type="sla.responseOverdue ? 'danger' : sla.firstRespondedAt ? 'success' : 'info'">{{ sla.responseOverdue ? '已超时' : sla.firstRespondedAt ? '已达标' : '计时中' }}</ElTag>
        <div>目标：{{ time(sla.responseDueAt) }}</div><div>实际：{{ time(sla.firstRespondedAt) }}</div>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="解决">
        <ElTag :type="sla.resolutionOverdue ? 'danger' : sla.resolvedAt ? 'success' : 'info'">{{ sla.resolutionOverdue ? '已超时' : sla.resolvedAt ? '已达标' : '计时中' }}</ElTag>
        <div>目标：{{ time(sla.resolutionDueAt) }}</div><div>实际：{{ time(sla.resolvedAt) }}</div>
      </ElDescriptionsItem>
    </ElDescriptions>
    <p class="sla-hint">北京时间（Asia/Shanghai），7×24 小时计时；挂起、转派不暂停。状态以最近加载为准。</p>
  </div>
</template>

<style scoped>
.sla-tags { display: flex; flex-wrap: wrap; gap: 5px; }
.sla-detail { line-height: 1.9; }
.sla-detail .el-tag { margin-bottom: 6px; }
.sla-hint, .sla-unknown { color: #64748b; font-size: 13px; }
</style>
