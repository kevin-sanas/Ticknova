<script setup lang="ts">
import { computed } from 'vue'
import { useSessionStore } from '../stores/session'
import { destinationFor } from '../access'
const props = defineProps<{ kind: string }>()
const session = useSessionStore()
const text = computed(() => props.kind === 'module' ? { code: '工作区已就绪', title: '当前账号暂未分配业务角色', body: '你可以通过知识管理查看已获授权的空间。如需处理工单或编辑知识，请联系管理员分配相应角色。' } : props.kind === 'forbidden' ? { code: '403', title: '此页面需要额外权限', body: '你可以返回自己的工作区，或联系管理员核对账号角色和队列权限。' } : { code: '404', title: '没有找到这个页面', body: '链接可能已经变更，请返回工作区继续。' })
</script>
<template><section class="panel state-panel"><span class="eyebrow">{{ text.code }}</span><h1>{{ text.title }}</h1><p class="muted">{{ text.body }}</p><router-link v-if="kind !== 'module'" :to="session.user ? destinationFor(session.user) : '/login'" class="primary-link">返回工作区</router-link><p v-else class="small muted">当前角色：{{ session.user?.roles.join('、') }}</p></section></template>
