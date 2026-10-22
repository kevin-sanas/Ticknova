<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAiContext, useAiRequest, useAiSession } from '../composables/ai'
import type { AiFeedback } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
import AiResultView from '../components/AiResultView.vue'
import AiRequestStatus from '../components/AiRequestStatus.vue'
const { identity, usable } = useAiSession()
const question = ref(String(useRoute().query.q || '').slice(0, 4000)); const spaceIds = ref<string[]>([]); const validation = ref('')
const payload = computed(() => ({ question: question.value.trim(), spaceIds: [...spaceIds.value].sort() }))
const payloadIdentity = computed(() => JSON.stringify(payload.value))
const request = useAiRequest(payloadIdentity)
const context = useAiContext(request.invalidate)
watch(identity, () => { question.value = ''; spaceIds.value = []; validation.value = '' }, { flush: 'sync' })
async function submit() {
  validation.value = ''
  if (!payload.value.question) { validation.value = '请先填写问题。'; return }
  if (payload.value.question.length > 4000 || spaceIds.value.length > 10) { validation.value = '问题最多 4,000 字，最多选择 10 个知识空间。'; return }
  if (!context.config.value || context.error.value || !usable.value) return
  await request.start('/ai/answers', payload.value)
}
async function feedback(choice: AiFeedback) { await request.feedback(choice) }
</script>
<template>
  <div class="page-heading"><div><h1>知识助手</h1><p class="muted">从你有权阅读的已发布知识中寻找答案，逐条核对引用。</p></div></div>
  <div v-if="!usable" class="info-notice" role="status">登录已失效或账号需要更新，请重新登录后使用知识助手。</div>
  <ErrorNotice :error="context.error.value" reload @reload="context.load" />
  <div class="assistant-layout">
    <section class="panel assistant-compose" aria-label="知识提问">
      <h2>提出问题</h2>
      <div v-if="context.config.value" class="info-notice ai-provider-notice">{{ context.config.value.externalProvider ? '第三方模型：问题与授权知识片段将发送给已配置的第三方服务。请确认内容适合该服务。' : '本地演示：使用确定性 mock 模型，无真实模型调用；结果用于演示和核对流程。' }}</div>
      <p v-if="context.loading.value" class="muted" role="status">正在加载知识范围与 AI 配置…</p>
      <form id="assistant-form" class="form-stack" @submit.prevent="submit">
        <div class="field"><label for="assistant-question">你的问题</label><textarea id="assistant-question" v-model="question" rows="5" maxlength="4000" required placeholder="例如：网络无法连接时，应先检查哪些项目？" :disabled="!usable"></textarea><small>{{ question.length.toLocaleString() }} / 4,000</small></div>
        <div class="field"><label for="assistant-spaces">知识空间（可多选）</label><select id="assistant-spaces" v-model="spaceIds" multiple :size="Math.min(Math.max(context.spaces.value.length, 2), 5)" :disabled="!usable || context.loading.value" aria-describedby="assistant-space-help"><option v-for="space in context.spaces.value" :key="space.id" :value="space.id">{{ space.name }}</option></select><small id="assistant-space-help">不选择表示提交时所有可读空间；最多选择 10 个。按住 Ctrl / ⌘ 可多选或取消。</small><small v-if="!context.loading.value && !context.spaces.value.length">当前没有可读空间，知识问题将得到证据不足提示。</small><el-button v-if="spaceIds.length" size="small" @click="spaceIds = []">使用所有可读空间</el-button></div>
        <p v-if="validation" class="error-text" role="alert">{{ validation }}</p>
        <div class="form-actions"><el-button id="assistant-submit" type="primary" native-type="submit" :loading="request.busy.value" :disabled="!question.trim() || !usable || !context.config.value || spaceIds.length > 10">查找答案</el-button></div>
      </form>
    </section>
    <section class="panel assistant-response" aria-label="回答与来源" :aria-busy="request.busy.value">
      <h2>回答与来源</h2>
      <AiRequestStatus :error="request.error.value" :job="request.job.value" :submitting="request.submitting.value" :waiting="request.waiting.value" :poll-pending="request.pollPending.value" :cancel-pending="request.cancelPending.value" @cancel="request.cancel" @refresh="request.refresh" />
      <AiResultView v-if="request.result.value" :result="request.result.value" :feedback-pending="request.feedbackPending.value" @feedback="feedback" />
      <div v-else-if="!request.busy.value && !request.error.value && !request.job.value" class="empty-state"><h3>从一个具体问题开始</h3><p>回答会附上文档版本、页码和片段；找不到可靠依据时会明确说明。</p></div>
    </section>
  </div>
</template>
