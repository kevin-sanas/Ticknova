<script setup lang="ts">
import { computed } from 'vue'
import type { KnowledgeAction } from '../types'
const props = defineProps<{ actions?: KnowledgeAction[]; pending?: boolean; secondary?: boolean }>()
const emit = defineEmits<{ action: [action: KnowledgeAction] }>()
const labels: Record<KnowledgeAction, string> = { EDIT_DRAFT: '编辑文章草稿', UPLOAD_VERSION: '上传新版本', SUBMIT_REVIEW: '提交审核', APPROVE: '审核通过', REJECT: '审核不通过', PUBLISH: '发布此版本', DISABLE: '撤回发布', RETRY_PARSE: '重试解析', RETRY_INDEX: '重试索引', ARCHIVE: '归档文档' }
const order: KnowledgeAction[] = ['PUBLISH', 'APPROVE', 'SUBMIT_REVIEW', 'EDIT_DRAFT', 'UPLOAD_VERSION', 'RETRY_PARSE', 'RETRY_INDEX', 'REJECT', 'DISABLE', 'ARCHIVE']
const visible = computed(() => order.filter(action => props.actions?.includes(action)))
const direct = computed(() => visible.value.filter(action => action !== 'ARCHIVE'))
const hasArchive = computed(() => visible.value.includes('ARCHIVE'))
const primary = computed(() => props.secondary ? undefined : direct.value.find(action => !['REJECT', 'DISABLE'].includes(action)))
function menuAction(action: KnowledgeAction) { emit('action', action) }
</script>
<template><div v-if="visible.length" class="document-actions" aria-label="文档操作"><el-button v-for="action in direct" :key="action" :type="['DISABLE', 'REJECT'].includes(action) ? 'danger' : action === primary ? 'primary' : 'default'" :plain="action !== primary" :loading="pending" @click="emit('action', action)">{{ labels[action] }}</el-button><el-dropdown v-if="hasArchive" trigger="click" placement="bottom-end" :disabled="pending" popper-class="document-action-menu" @command="menuAction"><el-button class="document-actions-more" aria-label="更多文档操作" :disabled="pending">更多 <span aria-hidden="true">⌄</span></el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="ARCHIVE" class="danger-menu-item">{{ labels.ARCHIVE }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></template>
<style scoped>
.document-actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.document-actions :deep(.el-button + .el-button) { margin-left: 0; }
.document-actions-more span { margin-left: var(--space-1); }
:global(.document-action-menu .danger-menu-item) { color: var(--color-danger); }
@media (max-width: 768px) { .document-actions :deep(.el-button) { min-height: var(--control-height-touch); } }
</style>
