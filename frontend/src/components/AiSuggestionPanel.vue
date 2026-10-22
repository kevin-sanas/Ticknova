<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { useAiContext, useAiRequest, useAiSession } from '../composables/ai'
import type { AiFeedback, AiKind, AiResult, Service } from '../types'
import ErrorNotice from './ErrorNotice.vue'
import AiRequestStatus from './AiRequestStatus.vue'
import AiResultView from './AiResultView.vue'
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
    <h2>AI 辅助</h2>
    <p class="small muted">基于工单标题、描述与公开回复生成建议，内部备注不参与。采纳后仍需人工核对并保存或发送。</p>
    <div v-if="context.config.value" class="info-notice ai-provider-notice">{{ context.config.value.externalProvider ? '第三方模型：公开工单内容与授权知识片段将发送给已配置的第三方服务。' : '本地演示：确定性 mock 模型，无真实模型调用。' }}</div>
    <ErrorNotice :error="context.error.value" reload @reload="context.load" />
    <form class="form-stack" @submit.prevent="generate">
      <div class="form-row"><div class="field"><label for="ai-kind">建议类型</label><select id="ai-kind" v-model="kind"><option value="REPLY">回复建议</option><option value="SUMMARY">工单摘要</option><option value="CLASSIFICATION">分类建议</option></select></div><div class="field"><label for="ai-spaces">知识空间（可多选）</label><select id="ai-spaces" v-model="spaceIds" multiple :size="Math.min(Math.max(context.spaces.value.length, 2), 4)"><option v-for="space in context.spaces.value" :key="space.id" :value="space.id">{{ space.name }}</option></select><small>不选择表示所有可读空间；最多 10 个。</small><el-button v-if="spaceIds.length" size="small" @click="spaceIds = []">使用所有可读空间</el-button></div></div>
      <div class="form-actions"><el-button id="ai-generate" native-type="submit" :loading="request.busy.value" :disabled="!usable || !context.config.value || spaceIds.length > 10">生成建议</el-button><small v-if="spaceIds.length > 10" class="error-text">最多选择 10 个知识空间。</small></div>
    </form>
    <AiRequestStatus :error="request.error.value" :job="request.job.value" :submitting="request.submitting.value" :waiting="request.waiting.value" :poll-pending="request.pollPending.value" :cancel-pending="request.cancelPending.value" @cancel="request.cancel" @refresh="request.refresh" />
    <AiResultView v-if="request.result.value" :result="request.result.value" :feedback-pending="request.feedbackPending.value" :service-name="serviceName" @feedback="feedback" />
    <div v-if="confirming && request.result.value" class="ai-replace-confirm" role="alertdialog" aria-label="确认替换现有草稿" aria-describedby="ai-replace-description"><p id="ai-replace-description">{{ kind === 'CLASSIFICATION' ? '采纳将替换当前优先级和标签输入，原输入不会保留。' : '采纳将替换当前公开回复草稿，原稿不会保留。' }}</p><div class="inline-actions"><el-button type="primary" :loading="request.feedbackPending.value" @click="feedback('ACCEPTED', true)">确认替换</el-button><el-button :disabled="request.feedbackPending.value" @click="confirming = false">保留原稿</el-button></div></div>
    <p v-if="notice" class="info-notice" role="status">{{ notice }}</p>
  </section>
</template>
