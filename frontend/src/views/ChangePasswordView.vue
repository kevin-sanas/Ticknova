<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { useSessionStore } from '../stores/session'
import { useOperation } from '../composables/operation'
import ErrorNotice from '../components/ErrorNotice.vue'
import AppIcon from '../components/AppIcon.vue'
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
  <section class="auth-card"><div class="auth-card-brand"><span><AppIcon name="inbox" :size="17" /></span>Ticknova</div><h1>设置新密码</h1><p class="muted">请设置新密码，保存后重新登录。</p>
    <ErrorNotice :error="operation.error.value" /><p v-if="validation" class="error-text" role="alert">{{ validation }}</p>
    <form class="form-stack" @submit.prevent="submit">
      <div class="field"><label for="old-password">当前密码</label><input id="old-password" v-model="form.oldPassword" type="password" autocomplete="current-password" placeholder="输入当前密码" required /></div>
      <div class="field"><label for="new-password">新密码</label><input id="new-password" v-model="form.newPassword" type="password" autocomplete="new-password" minlength="12" required aria-describedby="password-hint" placeholder="至少 12 个字符" /><small id="password-hint">至少 12 个字符，UTF-8 长度最多 72 字节。</small></div>
      <div class="field"><label for="confirmation">确认新密码</label><input id="confirmation" v-model="form.confirmation" type="password" autocomplete="new-password" minlength="12" placeholder="再次输入新密码" required /></div>
      <el-button type="primary" native-type="submit" :loading="operation.pending.value" class="wide-button">保存并重新登录</el-button>
    </form>
  </section>
</template>
<style scoped>
.auth-card-brand{display:flex;align-items:center;gap:9px;margin-bottom:23px;color:#355d83;font-size:16px;font-weight:700}.auth-card-brand span{display:grid;place-items:center;width:30px;height:30px;border-radius:7px;background:#e9f1f8;color:#426e99}
.auth-card{padding:36px;border:1px solid #d9e1ea;border-radius:12px;background:#fff;box-shadow:0 10px 30px rgba(34,54,76,.06)}.auth-card h1{font-size:26px;line-height:1.3;color:#182b40;margin:0 0 8px}.auth-card>.muted{color:#62758a;line-height:1.6;margin-bottom:26px}.auth-card :deep(.form-stack){gap:18px}.auth-card :deep(.field label){font-size:14px;font-weight:600;color:#293e54}.auth-card :deep(.field input){height:46px;border:1px solid #cbd7e3;border-radius:7px;padding:0 13px;font-size:14px;background:#fff}.auth-card :deep(.field input:focus-visible){outline:2px solid #84a9cc;outline-offset:1px}.auth-card :deep(.field small){line-height:1.5;color:#64778a}.auth-card :deep(.wide-button){height:46px;margin-top:4px;border-radius:7px;font-weight:600}@media(max-width:500px){.auth-card{padding:26px 22px}}
</style>
