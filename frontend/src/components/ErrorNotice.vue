<script setup lang="ts">
import { computed } from 'vue'
import type { ApiError } from '../api'
const props = defineProps<{ error: ApiError | null; reload?: boolean; login?: boolean }>()
defineEmits<{ reload: [] }>()
const versionConflict = computed(() => props.error?.status === 409 && props.reload)
const heading = computed(() => props.error?.status === 403 ? '你没有执行此操作的权限' : versionConflict.value ? '工单已发生变化，请先载入最新版本' : props.error?.status === 401 && !props.login ? '登录已失效，请重新登录' : props.error?.message)
const fields = computed(() => Object.entries(props.error?.problem.errors || {}).map(([key, value]) => `${key}：${typeof value === 'string' ? value : JSON.stringify(value)}`))
</script>
<template>
  <div v-if="error" class="error-notice" role="alert">
    <strong>{{ heading }}</strong>
    <p v-if="versionConflict">本次输入已保留。载入后请核对最新工单，再提交操作。</p>
    <p v-else-if="error.status === 403">当前账号或队列权限不足，可返回列表或联系管理员。</p>
    <ul v-if="fields.length"><li v-for="field in fields" :key="field">{{ field }}</li></ul>
    <span v-if="error.problem.traceId" class="trace">追踪编号：{{ error.problem.traceId }}</span>
    <el-button v-if="reload" size="small" @click="$emit('reload')">{{ error.status === 409 ? '载入最新版本' : '重试' }}</el-button>
  </div>
</template>
