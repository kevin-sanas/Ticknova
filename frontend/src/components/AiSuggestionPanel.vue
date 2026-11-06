<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { useAiContext, useAiRequest, useAiSession } from '../composables/ai'
import type { AiFeedback, AiKind, AiResult, Service } from '../types'
import ErrorNotice from './ErrorNotice.vue'
import AiRequestStatus from './AiRequestStatus.vue'
import AiResultView from './AiResultView.vue'
import AppIcon from './AppIcon.vue'
const props = defineProps<{ ticketId: string; ticketVersion: number; canEdit: boolean; publicDraft: string; priorityDraft: string; tagsDraft: string }>()
const emit = defineEmits<{ accepted: [result: AiResult]; unavailable: [] }>()
const { identity, usable } = useAiSession()
const kind = ref<Exclude<AiKind, 'ANSWER'>>('REPLY'); const spaceIds = ref<string[]>([]); const confirming = ref(false); const notice = ref(''); const services = ref<Service[]>([])
const payload = computed(() => ({ kind: kind.value, spaceIds: [...spaceIds.value].sort() }))
const payloadIdentity = computed(() => JSON.stringify([props.ticketId, props.ticketVersion, payload.value]))
const request = useAiRequest(payloadIdentity, () => emit('unavailable'))
const context = useAiContext(() => { request.invalidate(); confirming.value = false; emit('unavailable') })
const target = computed(() => kind.value === 'CLASSIFICATION' ? JSON.stringify([props.canEdit, props.priorityDraft, props.tagsDraft]) : props.publicDraft)
const replacesDraft = computed(() => kind.value === 'CLASSIFICATION' ? props.canEdit && !!(props.priorityDraft || props.tagsDraft.trim()) : !!props.publicDraft.trim())
const serviceName = computed(() => services.value.find(service => service.id === request.result.value?.serviceId)?.name)
let serviceGeneration = 0; let serviceController: AbortController | undefined
async function loadServices() {
  const value = ++serviceGeneration; serviceController?.abort(); serviceController = new AbortController(); services.value = []
  if (!usable.value) return
  try { const next = await api.get<Service[]>('/services', { signal: serviceController.signal }); if (value === serviceGeneration && Array.isArray(next)) services.value = next.filter(service => service.active) } catch { /* A catalog failure must not block review of the validated suggestion. */ }
}
watch([payloadIdentity, identity], () => { confirming.value = false; notice.value = '' }, { flush: 'sync' })
watch(identity, () => { spaceIds.value = []; void loadServices() }, { flush: 'sync' })
watch(() => request.result.value, next => { if (!next) confirming.value = false })
onMounted(loadServices); onBeforeUnmount(() => { serviceGeneration++; serviceController?.abort() })
async function generate() { notice.value = ''; confirming.value = false; if (context.config.value && usable.value && spaceIds.value.length <= 10) await request.start(`/tickets/${props.ticketId}/ai-drafts`, payload.value) }
async function feedback(choice: AiFeedback, confirmed = false) {
  const suggestion = request.result.value
  const text = suggestion?.kind === 'SUMMARY' ? suggestion.summary : suggestion?.kind === 'REPLY' ? suggestion.answer : null
  if (choice === 'ACCEPTED' && text && text.length > 10000) {
    confirming.value = false
    notice.value = '建议超过公开回复的 10,000 字符上限，请重新生成。原稿已保留。'
    return
  }
  if (choice === 'ACCEPTED' && replacesDraft.value && !confirmed) { confirming.value = true; return }
  const before = target.value; const next = await request.feedback(choice)
  if (!next) { confirming.value = false; return }
  if (choice === 'ACCEPTED') {
    if (target.value !== before) { confirming.value = true; notice.value = '输入在反馈期间发生变化，请重新确认后填入。'; return }
    emit('accepted', next)
    notice.value = next.kind === 'CLASSIFICATION' ? props.canEdit ? '建议已填入优先级和标签，请核对后点击“保存属性”。' : '已记录采纳；当前账号无属性编辑权限，可联系处理人员核对。' : '建议已填入公开回复草稿，请核对后点击“发送回复”。'
  }
  confirming.value = false
}
</script>
<template>
  <section class="panel ai-suggestion-panel" aria-label="工单 AI 辅助">
    <h2 class="copilot-title"><AppIcon name="ai" :size="20" />AI Copilot</h2>
    <p class="copilot-subtitle">结合工单与授权知识，辅助处理当前问题。</p>
    <div v-if="context.config.value" class="info-notice ai-provider-notice">{{ context.config.value.externalProvider ? '第三方模型：公开工单内容与授权知识片段将发送给已配置的第三方服务。' : '本地演示：确定性 mock 模型，无真实模型调用。' }}</div>
    <ErrorNotice :error="context.error.value" reload @reload="context.load" />
    <form class="form-stack" @submit.prevent="generate">
      <div id="ai-kind" class="copilot-kinds" role="group" aria-label="建议类型"><button type="button" data-ai-kind="REPLY" :aria-pressed="kind === 'REPLY'" @click="kind = 'REPLY'">回复建议</button><button type="button" data-ai-kind="SUMMARY" :aria-pressed="kind === 'SUMMARY'" @click="kind = 'SUMMARY'">工单摘要</button><button type="button" data-ai-kind="CLASSIFICATION" :aria-pressed="kind === 'CLASSIFICATION'" @click="kind = 'CLASSIFICATION'">分类建议</button></div>
      <div class="copilot-context"><strong>输入信息</strong><span>工单标题、问题描述与公开回复</span><small>内部备注不参与</small></div>
      <div class="field"><label for="ai-spaces">知识空间（可多选）</label><el-select id="ai-spaces" v-model="spaceIds" multiple filterable collapse-tags collapse-tags-tooltip :max-collapse-tags="2" :multiple-limit="10" :loading="context.loading.value" placeholder="所有可读空间" aria-label="知识空间（可多选）"><el-option v-for="space in context.spaces.value" :key="space.id" :value="space.id" :label="space.name" /></el-select><small>不选择表示所有可读空间；最多 10 个。</small><el-button v-if="spaceIds.length" size="small" @click="spaceIds = []">使用所有可读空间</el-button></div>
      <div class="copilot-actions"><el-button id="ai-generate" type="primary" native-type="submit" :loading="request.busy.value" :disabled="!usable || !context.config.value || spaceIds.length > 10"><AppIcon name="ai" :size="16" />生成建议</el-button><small v-if="spaceIds.length > 10" class="error-text">最多选择 10 个知识空间。</small></div>
    </form>
    <AiRequestStatus :error="request.error.value" :job="request.job.value" :submitting="request.submitting.value" :waiting="request.waiting.value" :poll-pending="request.pollPending.value" :cancel-pending="request.cancelPending.value" @cancel="request.cancel" @refresh="request.refresh" />
    <AiResultView v-if="request.result.value" :result="request.result.value" :feedback-pending="request.feedbackPending.value" :service-name="serviceName" @feedback="feedback" />
    <div v-else-if="!request.busy.value && !request.error.value && !request.job.value" class="copilot-empty"><AppIcon name="ai" :size="19" /><strong>准备好后生成建议</strong><span>结果会在这里显示，不会自动采纳。</span></div>
    <div v-if="confirming && request.result.value" class="ai-replace-confirm" role="alertdialog" aria-label="确认替换现有草稿" aria-describedby="ai-replace-description"><p id="ai-replace-description">{{ kind === 'CLASSIFICATION' ? '采纳将替换当前优先级和标签输入，原输入不会保留。' : '采纳将替换当前公开回复草稿，原稿不会保留。' }}</p><div class="inline-actions"><el-button type="primary" :loading="request.feedbackPending.value" @click="feedback('ACCEPTED', true)">确认替换</el-button><el-button :disabled="request.feedbackPending.value" @click="confirming = false">保留原稿</el-button></div></div>
    <p v-if="notice" class="info-notice" role="status">{{ notice }}</p>
    <p class="copilot-review">采纳建议后请人工核对，再保存或发送。</p>
  </section>
</template>
<style scoped>
.ai-suggestion-panel{min-width:0;padding:18px;background:#f7fafd;border:1px solid #dce3eb;border-radius:9px}.ai-suggestion-panel .form-stack{gap:12px}.ai-suggestion-panel .ai-provider-notice{padding:8px 10px;margin:0 0 12px;font-size:12px;line-height:1.5}.copilot-title{display:flex;align-items:center;gap:8px;margin:0;font-size:17px;color:#1d3043}.copilot-title :deep(.app-icon){color:#527aa2}.copilot-subtitle{margin:5px 0 14px;font-size:12px;line-height:1.5;color:#64788b}.copilot-kinds{display:flex;gap:3px;padding:3px;background:#eaf0f5;border-radius:7px}.copilot-kinds button{flex:1;min-width:0;min-height:36px;padding:7px 4px;border:0;border-radius:5px;background:transparent;color:#5b6f82;font:inherit;font-size:12px;cursor:pointer;white-space:nowrap}.copilot-kinds button[aria-pressed=true]{background:#d6e6f8;color:#315f8d;font-weight:700}.copilot-kinds button:focus-visible{outline:2px solid #4776ad;outline-offset:2px}.copilot-context{display:grid;gap:4px;padding:11px 12px;border:1px solid #e1eaf2;border-radius:7px;background:#edf4fa;font-size:12px;color:#536c82}.copilot-context strong{color:#324e68;font-size:12px}.copilot-context small{font-size:11px;color:#687e91}.field :deep(.el-select){width:100%}.copilot-actions{display:grid;gap:6px}.copilot-actions :deep(.el-button){width:100%;margin:0}.copilot-actions :deep(.el-button span){gap:6px}.copilot-empty{display:flex;flex-direction:column;align-items:center;gap:6px;padding:13px 8px;margin-top:12px;border:1px dashed #d6e2ed;border-radius:7px;background:#f2f7fb;text-align:center;color:#6b8193;font-size:11px;line-height:1.5}.copilot-empty strong{font-size:12px;color:#526c84}.copilot-review{font-size:11px;color:#667b8d;margin:12px 0 0;line-height:1.5}@media(prefers-reduced-motion:reduce){.copilot-kinds button{transition:none}}
</style>
