<script setup lang="ts">
import type { ApiError } from '../api'
import type { AiJob } from '../types'
import ErrorNotice from './ErrorNotice.vue'
defineProps<{ error: ApiError | null; job: AiJob | null; submitting: boolean; waiting: boolean; pollPending: boolean; cancelPending?: boolean }>()
defineEmits<{ refresh: []; cancel: [] }>()
const labels = { QUEUED: '排队中', RUNNING: '生成中', RETRYING: '自动重试', COMPLETED: '生成完成', FAILED: '生成失败', CANCELLED: '已中止，可重新生成' }
</script>
<template>
  <ErrorNotice :error="error" />
  <p v-if="error?.problem.code" class="small muted ai-error-code">错误码：{{ error.problem.code }}</p>
  <p v-if="error?.status === 409" class="info-notice">本次输入已保留，请核对请求后重试。</p>
  <div v-if="submitting && !job" class="ai-status ai-status-active" role="status"><span class="ai-status-label"><span class="ai-status-indicator" aria-hidden="true" />正在提交 AI 请求…</span></div>
  <div v-if="job" class="ai-status" :class="`ai-status-${job.status.toLowerCase()}`"><span class="ai-status-label" role="status"><span class="ai-status-indicator" aria-hidden="true" />{{ labels[job.status] }}<template v-if="job.attemptCount"> · 第 {{ job.attemptCount }} 次处理</template></span><div class="inline-actions"><el-button v-if="['QUEUED','RUNNING','RETRYING'].includes(job.status)" size="small" :loading="cancelPending" @click="$emit('cancel')">中止生成</el-button><el-button v-if="job.status !== 'CANCELLED'" size="small" :disabled="pollPending || submitting || cancelPending" @click="$emit('refresh')">{{ waiting ? '继续查询' : '刷新结果' }}</el-button></div></div>
  <p v-if="waiting" class="info-notice" role="status">等待时间较长，已暂停自动查询。可继续查询此任务，无需重新提交。</p>
</template>
