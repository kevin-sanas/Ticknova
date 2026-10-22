<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { useSessionStore } from '../stores/session'
import { useOperation } from '../composables/operation'
import ErrorNotice from '../components/ErrorNotice.vue'
const form = reactive({ oldPassword: '', newPassword: '', confirmation: '' }); const validation = ref('')
const session = useSessionStore(); const router = useRouter(); const operation = useOperation()
async function submit() {
  validation.value = ''
  if (form.newPassword !== form.confirmation) { validation.value = '两次输入的新密码不一致。'; return }
  if (form.newPassword === form.oldPassword) { validation.value = '新密码不能与当前密码相同。'; return }
  await operation.run(async () => { await api.post('/auth/change-password', { oldPassword: form.oldPassword, newPassword: form.newPassword }); form.oldPassword = ''; form.newPassword = ''; form.confirmation = ''; session.clear('密码修改成功，请使用新密码登录。'); await router.replace('/login') })
}
</script>
<template>
  <div class="auth-brand"><span class="brand-mark" aria-hidden="true">t</span>Ticknova</div>
  <section class="auth-card"><h1>设置新密码</h1><p class="muted">首次登录，请先替换管理员提供的初始密码。</p>
    <ErrorNotice :error="operation.error.value" /><p v-if="validation" class="error-text" role="alert">{{ validation }}</p>
    <form class="form-stack" @submit.prevent="submit">
      <div class="field"><label for="old-password">当前密码</label><input id="old-password" v-model="form.oldPassword" type="password" autocomplete="current-password" required /></div>
      <div class="field"><label for="new-password">新密码</label><input id="new-password" v-model="form.newPassword" type="password" autocomplete="new-password" minlength="12" required aria-describedby="password-hint" /><small id="password-hint">至少 12 个字符，UTF-8 长度最多 72 字节。</small></div>
      <div class="field"><label for="confirmation">确认新密码</label><input id="confirmation" v-model="form.confirmation" type="password" autocomplete="new-password" minlength="12" required /></div>
      <el-button type="primary" native-type="submit" :loading="operation.pending.value" class="wide-button">保存并重新登录</el-button>
    </form>
  </section>
</template>
