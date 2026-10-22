<script setup lang="ts">
import type { ApiError } from '../api'
defineProps<{ error: ApiError | null; retry?: boolean }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <div v-if="error" class="error-notice" role="alert">
    <strong>{{ error.status === 403 ? '当前账号没有此空间或操作的权限' : error.message }}</strong>
    <p v-if="error.status === 403">请联系空间管理员确认访问权限，然后重新加载。</p>
    <p v-if="error.status === 409">数据或处理状态已变化，本次输入已保留。请刷新核对后重试。</p>
    <span v-if="error.problem.code">错误代码：{{ error.problem.code }}</span>
    <span v-if="error.problem.traceId" class="trace">追踪编号：{{ error.problem.traceId }}</span>
    <el-button v-if="retry" size="small" @click="$emit('retry')">重新加载</el-button>
  </div>
</template>
