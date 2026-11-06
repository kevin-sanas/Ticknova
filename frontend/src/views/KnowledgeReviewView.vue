<script setup lang="ts">
import { useRouter } from 'vue-router'
import KnowledgeReviewQueue from '../components/KnowledgeReviewQueue.vue'
import type { KnowledgeReviewTask } from '../types'

const router = useRouter()
async function open(task: KnowledgeReviewTask) {
  await router.push({ path: `/knowledge/documents/${task.documentId}`, query: { versionId: task.versionId, fromReview: '1', tab: 'versions' } })
}
</script>

<template>
  <div class="page-heading"><div><h1>审核待办</h1><p class="muted">仅显示你当前有权审核、且由另一位编辑者提交的知识版本。</p></div><router-link class="review-back-link" to="/knowledge">返回知识协同</router-link></div>
  <section class="panel knowledge-section knowledge-review-page"><KnowledgeReviewQueue @open="open" /></section>
</template>

<style scoped>
.page-heading h1{font-size:25px;margin:0 0 6px}.page-heading p{font-size:13px}.review-back-link{display:inline-flex;padding:9px 12px;border:1px solid #d3dce6;border-radius:7px;background:#fff;color:#345c83;text-decoration:none;font-size:13px;font-weight:600}.knowledge-review-page{padding:20px;border:1px solid #dce2e8;border-radius:9px;background:#fff}.knowledge-review-page :deep(.knowledge-documents){padding:0;list-style:none}.knowledge-review-page :deep(.review-task-list li){display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 12px;border-bottom:1px solid #e6ebf0}.knowledge-review-page :deep(.knowledge-document-label){display:flex;flex-direction:column;gap:7px}.knowledge-review-page :deep(.review-task-open){border:1px solid #cbd9e7;border-radius:6px;padding:8px 12px;background:#fff;color:#315d87;cursor:pointer}.knowledge-review-page :deep(.review-task-open:hover){background:#edf4fb}@media(max-width:600px){.knowledge-review-page :deep(.review-task-list li){align-items:flex-start;flex-direction:column}}
</style>
