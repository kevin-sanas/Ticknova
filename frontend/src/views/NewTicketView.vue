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
const router = useRouter(); const operation = useOperation(); const catalog = useOperation(); const services = ref<Service[]>([])
const form = reactive({ title: '', description: '', serviceId: '', priority: 'NORMAL' }); const key = createRequestKey()
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
  <router-link :to="returnPath" class="back-link">← 返回工单工作台</router-link>
  <div class="page-heading"><div><h1>新建工单</h1><p class="muted">详细描述遇到的问题，帮助团队更快定位和处理。</p></div></div>
  <section class="panel" style="max-width:900px"><ErrorNotice :error="operation.error.value" /><ErrorNotice :error="catalog.error.value" reload @reload="loadServices" />
    <form class="form-stack" @submit.prevent="submit">
      <div class="field"><label for="ticket-title">问题标题 <span class="muted">（必填）</span></label><input id="ticket-title" v-model="form.title" :disabled="operation.pending.value" maxlength="200" required placeholder="用一句话概括遇到的问题" /><small>{{ form.title.length }} / 200</small></div>
      <div class="form-row"><div class="field"><label for="ticket-service">服务目录</label><select id="ticket-service" v-model="form.serviceId" :disabled="catalog.pending.value || operation.pending.value"><option value="">暂不确定 / 未指定</option><option v-for="service in services" :key="service.id" :value="service.id">{{ service.name }}</option></select><small>选择最接近的服务，后续由团队进行分诊。</small></div><div class="field"><label for="ticket-priority">优先级</label><select id="ticket-priority" v-model="form.priority" :disabled="operation.pending.value"><option v-for="(label,value) in priorities" :key="value" :value="value">{{ label }}</option></select><small>请根据影响范围和紧迫程度选择。</small></div></div>
      <div class="field"><label for="ticket-description">问题描述 <span class="muted">（必填）</span></label><textarea id="ticket-description" v-model="form.description" :disabled="operation.pending.value" rows="10" maxlength="20000" required placeholder="请说明发生时间、影响范围、操作步骤及已尝试的解决方式。请勿填写密码或敏感凭据。"></textarea><small>{{ form.description.length.toLocaleString() }} / 20,000 · 文本将按原样显示</small></div>
      <div class="info-notice" style="margin:0">工单会进入默认受理队列。服务团队分诊后，你可以在工单中查看进展并补充信息。</div>
      <div class="form-actions"><el-button type="primary" native-type="submit" :loading="operation.pending.value" :disabled="operation.pending.value || !form.title.trim() || !form.description.trim()">提交工单</el-button><router-link :to="returnPath">取消</router-link><span v-if="operation.pending.value" class="small muted" role="status">正在提交，请勿关闭页面…</span></div>
    </form>
  </section>
</template>
