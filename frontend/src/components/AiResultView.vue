<script setup lang="ts">
import { computed } from 'vue'
import { priorities, type AiFeedback, type AiResult } from '../types'
const props = defineProps<{ result: AiResult; feedbackPending: boolean; serviceName?: string }>()
defineEmits<{ feedback: [choice: AiFeedback] }>()
const requiresEvidence = computed(() => ['ANSWER', 'REPLY'].includes(props.result.kind))
const refused = computed(() => props.result.outcome !== 'ANSWERED' || (requiresEvidence.value && !props.result.citations.length))
const kindNames = { ANSWER: '知识回答', REPLY: '回复建议', SUMMARY: '工单摘要', CLASSIFICATION: '分类建议' }
</script>
<template>
  <article class="ai-result" aria-label="AI 生成结果">
    <div class="panel-header"><h3>{{ kindNames[result.kind] }}</h3><span class="badge">{{ result.mode === 'mock' ? '本地演示' : '第三方模型' }}</span></div>
    <div v-if="refused" class="info-notice" role="status"><strong>证据不足，暂时无法确认</strong><p>当前可读、已发布的知识资料不足以支持回答。可调整问题或知识范围，或通过工单联系服务团队。</p></div>
    <template v-else>
      <p v-if="result.kind === 'CLASSIFICATION'" class="small muted">仅供人工判断；服务目录建议仅展示。</p>
      <dl v-if="result.kind === 'CLASSIFICATION'" class="ai-classification"><div><dt>服务目录建议</dt><dd>{{ serviceName || result.serviceId || '未建议' }}</dd></div><div><dt>优先级建议</dt><dd>{{ priorities[result.priority || ''] || result.priority || '未建议' }}</dd></div><div><dt>标签建议</dt><dd>{{ result.tags.join('、') || '未建议' }}</dd></div></dl>
      <div v-else class="plain-text ai-answer">{{ result.kind === 'SUMMARY' ? result.summary : result.answer }}</div>
      <p v-if="!result.citations.length && !requiresEvidence" class="small muted ai-ticket-only">仅依据工单公开内容，未引用知识库</p>
      <section v-if="result.citations.length" class="ai-citations" aria-label="知识引用"><h3>知识引用 · {{ result.citations.length }}</h3><ol><li v-for="source in result.citations" :key="source.chunkId"><div class="ai-citation-heading"><strong>{{ source.title }}</strong><span>v{{ source.version }}<template v-if="source.page !== null"> · 第 {{ source.page }} 页</template></span></div><details open><summary>查看已验证片段</summary><p class="plain-text">{{ source.snippet }}</p><router-link :to="{ path: `/knowledge/documents/${source.documentId}`, query: { versionId: source.versionId, chunkId: source.chunkId } }">查看来源位置 →</router-link><small class="citation-permission">访问时重新校验权限；来源已撤回或更新时，请重新生成。</small></details></li></ol></section>
    </template>
    <p class="ai-provenance">模型配置：{{ result.profile }}<br />AI 结果需人工核对后使用。</p>
    <div class="ai-feedback"><span v-if="result.feedback" role="status" class="small">{{ result.feedback === 'ACCEPTED' ? '已采纳' : '已拒绝' }}</span><template v-else><el-button :loading="feedbackPending" :disabled="feedbackPending || refused" @click="$emit('feedback', 'ACCEPTED')">采纳建议</el-button><el-button :disabled="feedbackPending" @click="$emit('feedback', 'REJECTED')">拒绝建议</el-button></template></div>
  </article>
</template>
