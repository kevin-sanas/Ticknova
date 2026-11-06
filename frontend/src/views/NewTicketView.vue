<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, createRequestKey } from '../api'
import { useOperation } from '../composables/operation'
import { useUnsavedChanges } from '../composables/unsaved'
import { useSessionStore } from '../stores/session'
import { ticketReturnPath } from '../composables/ticketScroll'
import type { Service, TicketSummary } from '../types'
import { priorities } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
import AppIcon from '../components/AppIcon.vue'
const router = useRouter(); const operation = useOperation(); const catalog = useOperation(); const services = ref<Service[]>([])
const form = reactive({ title: '', description: '', serviceId: '', priority: 'NORMAL' }); const key = createRequestKey()
const serviceHint = computed(() => {
  const name = services.value.find(service => service.id === form.serviceId)?.name || ''
  if (/网络|连接|VPN|wifi|wi-fi/i.test(name)) return '说明网络连接方式、访问的系统，以及出现问题时的提示。'
  if (/账号|权限|登录/.test(name)) return '说明无法访问的系统、操作步骤及出现的问题，请勿填写密码。'
  if (/软件|办公|邮箱/.test(name)) return '说明软件名称、操作步骤和出现的错误提示。'
  if (/设备|电脑|硬件/.test(name)) return '说明设备类型、故障现象和出现问题时的操作。'
  return '按顺序写下操作，并说明具体出现了什么问题。'
})
const priorityHint = computed(() => form.priority === 'URGENT' || form.priority === 'HIGH'
  ? '说明何时开始、影响范围和受阻的工作，帮助团队判断紧迫程度。'
  : '说明何时开始、哪些工作受到影响。例如：今天上午起无法远程办公。')
const session = useSessionStore(); const submitted = ref(false); let generation = 0
const returnPath = computed(() => ticketReturnPath(JSON.stringify([session.user?.id, session.expiresAt])))
useUnsavedChanges(() => session.authenticated && !submitted.value && (!!form.title || !!form.description || !!form.serviceId || form.priority !== 'NORMAL'))
watch(() => session.token, () => { generation++; services.value = []; Object.assign(form, { title: '', description: '', serviceId: '', priority: 'NORMAL' }); key.reset() }, { flush: 'sync' })
onBeforeUnmount(() => { generation++ })
async function loadServices() { const current = generation; const token = session.token; await catalog.run(async () => { const next = await api.get<Service[]>('/services'); if (current === generation && token === session.token) services.value = next }) }
onMounted(loadServices)
async function submit() {
  if (operation.pending.value) return
  if (!form.title.trim() || !form.description.trim()) return
  const payload = { title: form.title.trim(), description: form.description.trim(), serviceId: form.serviceId || null, priority: form.priority }
  const current = generation; const token = session.token
  await operation.run(async () => { const ticket = await api.post<TicketSummary>('/tickets', payload, { key: key.for(payload) }); if (current !== generation || token !== session.token) return; submitted.value = true; key.reset(); await router.push(`/tickets/${ticket.id}`) })
}
</script>
<template>
  <div class="new-ticket-page">
    <header class="new-ticket-heading">
      <router-link :to="returnPath" class="new-ticket-back"><span><AppIcon name="back" :size="18" /></span>返回</router-link>
      <h1>新建工单</h1><p>提交您遇到的问题，我们将尽快为您提供支持。</p>
    </header>
    <div class="new-ticket-layout">
      <form class="panel new-ticket-form" @submit.prevent="submit">
        <ErrorNotice :error="operation.error.value" /><ErrorNotice :error="catalog.error.value" reload @reload="loadServices" />
        <div class="new-ticket-fields">
          <div class="field"><label for="ticket-title">标题 <span class="required-mark" aria-hidden="true">*</span><span class="sr-only">（必填）</span></label><input id="ticket-title" v-model="form.title" :disabled="operation.pending.value" maxlength="200" required placeholder="请简要概括您的问题（如：公司 VPN 无法连接）" /><small>{{ form.title.length }} / 200</small></div>
          <div class="field"><label for="ticket-description">问题描述 <span class="required-mark" aria-hidden="true">*</span><span class="sr-only">（必填）</span></label><textarea id="ticket-description" v-model="form.description" :disabled="operation.pending.value" rows="6" maxlength="20000" required placeholder="请详细描述您遇到的问题，包括现象、影响范围、出现时间等。请勿填写密码等凭据。"></textarea><small>{{ form.description.length.toLocaleString() }} / 20,000 · 文本将按原样显示</small></div>
          <div class="new-ticket-row"><div class="field"><label for="ticket-service">服务目录</label><select id="ticket-service" v-model="form.serviceId" :disabled="catalog.pending.value || operation.pending.value"><option value="">暂不确定 / 未指定</option><option v-for="service in services" :key="service.id" :value="service.id">{{ service.name }}</option></select></div><div class="field"><label for="ticket-priority">优先级</label><select id="ticket-priority" v-model="form.priority" :disabled="operation.pending.value"><option v-for="(label,value) in priorities" :key="value" :value="value">{{ label }}</option></select></div></div>
          <p class="new-ticket-notice">工单将进入默认受理队列，分诊后可在工单中查看进展并补充信息。</p>
        </div>
        <div class="new-ticket-actions"><span v-if="operation.pending.value" role="status">正在提交，请勿关闭页面…</span><router-link :to="returnPath" class="cancel-link">取消</router-link><el-button type="primary" native-type="submit" :loading="operation.pending.value" :disabled="operation.pending.value || !form.title.trim() || !form.description.trim()">提交工单</el-button></div>
      </form>
      <aside class="new-ticket-help" aria-labelledby="ticket-help-title"><h2 id="ticket-help-title">如何描述更清楚</h2><p>可参考以下提示，无需逐项填写：</p><div class="help-rule"></div><section><h3>发生时间与影响</h3><p aria-live="polite">{{ priorityHint }}</p></section><section><h3>复现步骤</h3><p aria-live="polite">{{ serviceHint }}</p></section><section><h3>已尝试的方法</h3><p>列出已做过的排查，例如重启客户端或切换网络。</p></section><section><h3>报错与信息保护</h3><p>可描述错误提示；如需提供截图，请先遮盖账号、密码等敏感信息。</p></section></aside>
    </div>
  </div>
</template>
<style scoped>
.new-ticket-page{color:#172738;max-width:1600px;margin:0 auto}.new-ticket-heading{margin-bottom:24px}.new-ticket-back{display:inline-flex;align-items:center;gap:12px;font-size:15px;font-weight:650;color:#24374b;text-decoration:none}.new-ticket-back span{display:grid;place-items:center;width:38px;height:36px;background:#e9e8e6;border-radius:6px}.new-ticket-heading h1{font-size:clamp(29px,3vw,38px);line-height:1.2;margin:22px 0 6px}.new-ticket-heading p{margin:0;color:#617081;font-size:15px}.new-ticket-layout{display:grid;grid-template-columns:minmax(0,2fr) minmax(240px,1fr);gap:16px;align-items:stretch}.new-ticket-form,.new-ticket-help{min-width:0;background:#fff;border:1px solid #d8dfe7;border-radius:9px}.new-ticket-form{padding:0;display:flex;flex-direction:column}.new-ticket-fields{display:grid;gap:17px;padding:24px}.new-ticket-fields .field{min-width:0}.new-ticket-fields label{font-size:14px;font-weight:650;color:#1d2e42}.new-ticket-fields input,.new-ticket-fields select,.new-ticket-fields textarea{margin-top:7px;border:1px solid #cbd5e0;border-radius:6px;width:100%;font-size:14px}.new-ticket-fields input,.new-ticket-fields select{min-height:42px}.new-ticket-fields textarea{min-height:132px;line-height:1.6;resize:vertical}.new-ticket-fields small{font-size:12px;color:#647589}.new-ticket-row{display:grid;grid-template-columns:1fr 1fr;gap:18px}.required-mark{color:#bd3c35}.new-ticket-notice{margin:0;color:#66798b;font-size:12px;line-height:1.5}.new-ticket-actions{display:flex;justify-content:flex-end;align-items:center;gap:12px;margin-top:auto;padding:16px 24px;border-top:1px solid #e9edf1}.new-ticket-actions span{margin-right:auto;font-size:12px;color:#607486}.cancel-link{display:inline-flex;align-items:center;justify-content:center;min-height:40px;padding:0 18px;border:1px solid #b7c8da;border-radius:7px;background:#fff;text-decoration:none;color:#385979;font-size:14px;font-weight:600}.cancel-link:hover{background:#f2f6fa;text-decoration:none}.new-ticket-help{padding:24px;color:#4b5d71}.new-ticket-help h2{margin:0;font-size:21px;color:#1b2c3e}.new-ticket-help>p{margin:9px 0 0;font-size:13px}.help-rule{height:1px;background:#e1e6eb;margin:18px 0}.new-ticket-help section+section{margin-top:18px}.new-ticket-help h3{font-size:14px;color:#26394c;margin:0 0 6px}.new-ticket-help section p{font-size:13px;line-height:1.65;margin:0}.new-ticket-back:focus-visible,.cancel-link:focus-visible{outline:2px solid #4776ad;outline-offset:3px}@media(max-width:800px){.new-ticket-layout{grid-template-columns:1fr}.new-ticket-help{padding:20px}.new-ticket-fields{padding:20px}.new-ticket-actions{padding:16px 20px}}@media(max-width:480px){.new-ticket-row{grid-template-columns:1fr}.new-ticket-heading h1{margin-top:18px}.new-ticket-actions{flex-wrap:wrap}}
</style>
