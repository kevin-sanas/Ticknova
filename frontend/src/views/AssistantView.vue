<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAiContext, useAiRequest, useAiSession } from '../composables/ai'
import type { AiFeedback } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
import AiResultView from '../components/AiResultView.vue'
import AiRequestStatus from '../components/AiRequestStatus.vue'
import AppIcon from '../components/AppIcon.vue'
const { identity, usable } = useAiSession()
const question = ref(String(useRoute().query.q || '').slice(0, 4000)); const spaceIds = ref<string[]>([]); const validation = ref('')
const questionInput = ref<HTMLTextAreaElement | null>(null)
const suggestions = ['网络连接异常时，应该如何排查？', '如何申请系统访问权限？', '办公软件安装失败怎么办？']
function suggest(text: string) {
  const draft = question.value
  if (!draft.split('\n').includes(text)) {
    const next = draft ? `${draft}\n${text}` : text
    if (next.length <= 4000) { question.value = next; validation.value = '' }
    else validation.value = '问题已接近字数上限，请先精简后再添加建议。'
  }
  void nextTick(() => questionInput.value?.focus())
}
const payload = computed(() => ({ question: question.value.trim(), spaceIds: [...spaceIds.value].sort() }))
const submittedQuestion = ref(''); const submittedPayloadIdentity = ref('')
const payloadIdentity = computed(() => submittedPayloadIdentity.value)
const request = useAiRequest(payloadIdentity)
const context = useAiContext(request.invalidate)
watch(identity, () => { question.value = ''; submittedQuestion.value = ''; submittedPayloadIdentity.value = ''; spaceIds.value = []; validation.value = '' }, { flush: 'sync' })
async function submit() {
  validation.value = ''
  if (!payload.value.question) { validation.value = '请先填写问题。'; return }
  if (payload.value.question.length > 4000 || spaceIds.value.length > 10) { validation.value = '问题最多 4,000 字，最多选择 10 个知识空间。'; return }
  if (!context.config.value || context.error.value || !usable.value || request.busy.value) return
  const submitted = payload.value
  submittedQuestion.value = submitted.question
  submittedPayloadIdentity.value = JSON.stringify(submitted)
  await request.start('/ai/answers', submitted)
}
async function feedback(choice: AiFeedback) { await request.feedback(choice) }
</script>
<template>
  <div class="assistant-page">
    <header class="assistant-heading"><div><h1>知识助手</h1><p>基于授权知识查找答案，回答可核对来源。</p></div><router-link class="assistant-library" to="/knowledge"><AppIcon name="book" :size="17" />浏览知识库</router-link></header>
    <div v-if="!usable" class="info-notice" role="status">登录已失效或账号需要更新，请重新登录后使用知识助手。</div>
    <ErrorNotice :error="context.error.value" reload @reload="context.load" />
    <div class="assistant-workspace">
      <section class="assistant-conversation" aria-label="知识助手对话">
        <div class="assistant-welcome"><img :src="'/knowledge-assistant-mascot.png'" alt="" /><div><h2>你好，我是知识助手</h2><p>从你有权阅读的已发布知识中寻找答案，逐条核对引用。</p></div><span v-if="context.config.value" class="badge ai-mode-badge">{{ context.config.value.externalProvider ? '第三方模型' : '本地演示' }}</span></div>
        <div class="assistant-thread" aria-live="polite">
          <div v-if="request.job.value || request.result.value" class="assistant-user-message"><span>你</span><p>{{ submittedQuestion }}</p></div>
          <section class="assistant-response" aria-label="回答与来源" :aria-busy="request.busy.value">
            <AiRequestStatus :error="request.error.value" :job="request.job.value" :submitting="request.submitting.value" :waiting="request.waiting.value" :poll-pending="request.pollPending.value" :cancel-pending="request.cancelPending.value" @cancel="request.cancel" @refresh="request.refresh" />
            <div v-if="request.result.value" class="assistant-answer-row"><img :src="'/knowledge-assistant-mascot.png'" alt="" /><AiResultView :result="request.result.value" :feedback-pending="request.feedbackPending.value" @feedback="feedback" /></div>
            <div v-else-if="!request.busy.value && !request.error.value && !request.job.value" class="assistant-empty"><h3>有什么想了解的？</h3><p>提出具体问题，我会从授权的知识文档中查找依据。</p></div>
          </section>
        </div>
        <form id="assistant-form" class="assistant-input" @submit.prevent="submit">
          <label class="sr-only" for="assistant-question">你的问题</label><textarea id="assistant-question" ref="questionInput" v-model="question" rows="3" maxlength="4000" required placeholder="输入你的问题，例如：网络无法连接时应该先检查什么？" :disabled="!usable"></textarea>
          <div class="assistant-input-footer"><small>{{ question.length.toLocaleString() }} / 4,000 · 请勿输入不适合模型处理的敏感信息</small><el-button id="assistant-submit" type="primary" native-type="submit" :loading="request.busy.value" :disabled="!question.trim() || !usable || !context.config.value || spaceIds.length > 10"><AppIcon name="ai" :size="16" />查找答案</el-button></div>
          <p v-if="validation" class="error-text" role="alert">{{ validation }}</p>
        </form>
      </section>
      <aside class="assistant-scope" aria-label="回答范围">
        <h2>回答范围</h2><p class="scope-intro">仅从你有权阅读的知识空间查找依据。</p>
        <p v-if="context.loading.value" class="muted" role="status">正在加载知识范围与 AI 配置…</p>
        <div class="field"><label for="assistant-spaces">知识空间（可多选）</label>
          <el-select id="assistant-spaces" v-model="spaceIds" class="assistant-space-select" multiple filterable collapse-tags collapse-tags-tooltip :max-collapse-tags="3" :multiple-limit="10" :loading="context.loading.value" :disabled="!usable || context.loading.value" placeholder="所有可读空间" aria-label="知识空间（可多选）" aria-describedby="assistant-space-help">
            <el-option v-for="space in context.spaces.value" :key="space.id" :label="space.name" :value="space.id" />
          </el-select>
          <small id="assistant-space-help">不选择表示查询所有可读空间；可输入名称筛选，最多选择 10 个。</small><small v-if="!context.loading.value && !context.spaces.value.length">当前没有可读空间，知识问题将得到证据不足提示。</small><el-button v-if="spaceIds.length" size="small" @click="spaceIds = []">使用所有可读空间</el-button>
        </div>
        <div class="assistant-suggestions"><h3>试试这样提问</h3><button v-for="(suggestion, index) in suggestions" :key="suggestion" type="button" data-suggested-question :style="{ '--suggestion-index': index }" @click="suggest(suggestion)">{{ suggestion }}<AppIcon name="chevron" :size="14" /></button></div>
        <details v-if="context.config.value" class="ai-disclosure"><summary>模型与数据说明</summary><p>{{ context.config.value.externalProvider ? '第三方模型：问题与授权知识片段将发送给已配置的第三方服务。请确认内容适合该服务。' : '本地演示：使用确定性 mock 模型，无真实模型调用；结果用于演示和核对流程。' }}</p></details>
      </aside>
    </div>
  </div>
</template>
<style scoped>
.assistant-page{color:#1d2e42;display:flex;flex-direction:column;gap:16px;min-width:0}.assistant-heading{display:flex;align-items:center;justify-content:space-between;gap:20px}.assistant-heading h1{font-size:25px;line-height:1.25;margin:0}.assistant-heading p{margin:5px 0 0;color:#62758a;font-size:13px}.assistant-library{display:inline-flex;gap:7px;align-items:center;border:1px solid #d2dbe5;border-radius:7px;background:#fff;color:#355b82;text-decoration:none;padding:9px 12px;white-space:nowrap;font-weight:600;font-size:13px}.assistant-workspace{display:grid;grid-template-columns:minmax(0,1fr) 290px;gap:16px;align-items:stretch;min-height:610px}.assistant-conversation,.assistant-scope{background:#fff;border:1px solid #dce2e8;border-radius:10px;min-width:0}.assistant-conversation{display:flex;flex-direction:column;min-height:0}.assistant-welcome{display:flex;align-items:center;gap:12px;padding:16px 20px;border-bottom:1px solid #e5e9ee}.assistant-welcome img,.assistant-answer-row img{object-fit:contain;flex:none;width:46px;height:46px;border-radius:12px;background:#eef3f9}.assistant-welcome h2{font-size:16px;margin:0}.assistant-welcome p{font-size:12px;color:#66798b;margin:4px 0 0}.assistant-welcome .badge{margin-left:auto;white-space:nowrap}.assistant-thread{padding:22px;flex:1;min-height:240px;overflow:auto}.assistant-empty{padding:48px 16px;text-align:center;color:#65798d}.assistant-empty h3{font-size:18px;color:#273e57;margin:0 0 8px}.assistant-empty p{font-size:13px;margin:0}.assistant-user-message{display:flex;align-items:start;justify-content:flex-end;gap:8px;margin-bottom:18px}.assistant-user-message span{order:2;display:grid;place-items:center;border-radius:50%;background:#dbe8f5;color:#294d73;width:32px;height:32px;flex:none;font-size:12px}.assistant-user-message p{white-space:pre-wrap;overflow-wrap:anywhere;border-radius:10px;background:#e7f0fa;padding:12px 15px;max-width:min(80%,680px);margin:0}.assistant-answer-row{display:flex;align-items:flex-start;gap:10px}.assistant-answer-row img{width:34px;height:34px}.assistant-answer-row :deep(.ai-result){min-width:0;width:fit-content;max-width:min(100%,760px);overflow-wrap:anywhere}.assistant-input{margin:0 22px 20px;padding:12px 14px;border:1px solid #cbd7e3;border-radius:10px;box-shadow:0 3px 12px #233e5b0d}.assistant-input textarea{display:block;width:100%;min-height:76px;max-height:180px;resize:vertical;border:0;box-shadow:none;outline:none;padding:2px;background:transparent;font-size:14px;line-height:1.6}.assistant-input:focus-within{outline:2px solid #7c9fc5;outline-offset:1px}.assistant-input-footer{display:flex;align-items:center;justify-content:space-between;gap:10px}.assistant-input-footer small{color:#64778a;font-size:11px}.assistant-scope{padding:20px}.assistant-scope h2{font-size:16px;margin:0}.scope-intro{font-size:13px;color:#66798b;line-height:1.55;margin:8px 0 20px}.assistant-scope .field{gap:8px}.assistant-scope label{font-weight:600;font-size:13px}.assistant-space-select{width:100%}.assistant-scope small{font-size:12px;line-height:1.5;color:#66798b}.assistant-suggestions{border-top:1px solid #e5e9ee;margin-top:24px;padding-top:20px}.assistant-suggestions h3{font-size:13px;margin:0 0 10px}.assistant-suggestions button{width:100%;display:flex;align-items:center;justify-content:space-between;gap:8px;text-align:left;padding:10px 11px;border:1px solid #e1e9f1;border-radius:7px;background:#f7f9fc;color:#365875;font-size:12px;margin-top:8px;cursor:pointer;animation:suggestion-in .25s ease-out backwards;animation-delay:calc(var(--suggestion-index)*90ms)}.assistant-suggestions button:hover{background:#edf3fa}.ai-disclosure{border-top:1px solid #e5e9ee;padding-top:18px;margin-top:22px;font-size:12px;color:#5f7286}.ai-disclosure summary{cursor:pointer;font-weight:600}.ai-disclosure p{margin:10px 0 0;line-height:1.6}@keyframes suggestion-in{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}@media(max-width:900px){.assistant-workspace{grid-template-columns:1fr}.assistant-scope{order:-1}}@media(max-width:600px){.assistant-heading{align-items:flex-start;flex-direction:column}.assistant-workspace{min-height:0}.assistant-thread{padding:15px}.assistant-input{margin:0 14px 14px}.assistant-input-footer{align-items:flex-start;flex-direction:column}.assistant-input-footer .el-button{align-self:flex-end}}@media(prefers-reduced-motion:reduce){.assistant-suggestions button{animation:none}}

.assistant-space-select :deep(.el-select__input:focus-visible){outline:none}
.ai-disclosure{border:1px solid var(--color-border);border-radius:var(--radius-control);background:var(--color-surface-muted);padding:0;margin-top:22px;color:var(--color-text-secondary);font-size:var(--text-xs)}
.ai-disclosure summary{padding:12px;min-height:44px;color:var(--color-text);font-size:var(--text-sm);line-height:20px}
.ai-disclosure summary:focus-visible{outline:2px solid var(--color-focus);outline-offset:2px;border-radius:var(--radius-control)}
.ai-disclosure p{padding:0 12px 12px;margin:0;line-height:1.6;overflow-wrap:anywhere}
</style>
