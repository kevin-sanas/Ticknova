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
