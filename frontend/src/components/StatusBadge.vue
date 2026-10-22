<script setup lang="ts">
import { priorities, statuses } from '../types'
defineProps<{ value: string; kind?: 'status' | 'priority' }>()
</script>
<template><span class="badge" :class="[kind || 'status', value.toLowerCase()]">{{ (kind === 'priority' ? priorities : statuses)[value] || value }}</span></template>
