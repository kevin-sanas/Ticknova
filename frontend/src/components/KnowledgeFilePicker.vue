<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  id: string
  label: string
  modelValue: File | null
  disabled?: boolean
  describedBy?: string
  issue?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [file: File | null] }>()
const input = ref<HTMLInputElement | null>(null)

function select() {
  if (!props.disabled) input.value?.click()
}
function changed(event: Event) {
  emit('update:modelValue', (event.target as HTMLInputElement).files?.[0] ?? null)
}
function clear() {
  if (props.disabled) return
  if (input.value) input.value.value = ''
  emit('update:modelValue', null)
}
function size(bytes: number) {
  return bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1024 / 1024).toFixed(1)} MiB`
}
</script>

<template>
  <div class="file-picker" :class="{ 'has-error': !!issue, 'is-disabled': disabled }">
    <input :id="id" ref="input" class="file-picker-native" type="file" accept=".pdf,.docx,.md,.txt" :disabled="disabled" tabindex="-1" :aria-label="label" :aria-describedby="[describedBy, issue ? `${id}-issue` : ''].filter(Boolean).join(' ') || undefined" @change="changed" />
    <button type="button" class="file-picker-trigger" :disabled="disabled" :aria-label="`${label}：选择文件`" @click="select">选择文件</button>
    <span class="file-picker-name" role="status">{{ modelValue ? `${modelValue.name} · ${size(modelValue.size)}` : '尚未选择文件' }}</span>
    <button v-if="modelValue" type="button" class="file-picker-clear" :disabled="disabled" :aria-label="`清除已选文件 ${modelValue.name}`" @click="clear">清除</button>
  </div>
  <small v-if="issue" :id="`${id}-issue`" class="file-picker-issue" role="alert">{{ issue }}</small>
</template>
