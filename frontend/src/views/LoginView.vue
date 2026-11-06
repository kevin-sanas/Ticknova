<script setup lang="ts">
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { destinationFor } from '../access'
import { useOperation } from '../composables/operation'
import ErrorNotice from '../components/ErrorNotice.vue'
import AppIcon from '../components/AppIcon.vue'
const session = useSessionStore(); const router = useRouter(); const operation = useOperation()
const form = reactive({ username: '', password: '' })
async function submit() { await operation.run(async () => { const user = await session.login(form.username.trim(), form.password); form.password = ''; await router.replace(destinationFor(user)) }) }
</script>
<template>
  <section class="auth-card" aria-labelledby="login-heading">
    <div class="auth-card-brand"><span><AppIcon name="inbox" :size="17" /></span>Ticknova</div>
    <h1 id="login-heading">欢迎回来</h1><p class="muted">登录账号，继续处理你的工作。</p>
    <div v-if="session.notice" class="info-notice" role="status">{{ session.notice }}</div>
    <ErrorNotice :error="operation.error.value" login />
    <form class="form-stack" @submit.prevent="submit">
      <div class="field"><label for="username">用户名</label><input id="username" v-model="form.username" name="username" autocomplete="username" maxlength="80" required placeholder="输入用户名" autofocus /></div>
      <div class="field"><label for="password">密码</label><input id="password" v-model="form.password" name="password" type="password" autocomplete="current-password" required placeholder="输入密码" /></div>
      <el-button type="primary" native-type="submit" :loading="operation.pending.value" class="wide-button">登录工作台</el-button>
    </form>
    <p class="auth-note">账号由管理员创建。首次登录需要修改初始密码。</p>
  </section>
  <p class="auth-footer">会话仅在当前页面有效，刷新后请重新登录。</p>
</template>
<style scoped>
.auth-card-brand{display:flex;align-items:center;gap:9px;margin-bottom:23px;color:#355d83;font-size:16px;font-weight:700}.auth-card-brand span{display:grid;place-items:center;width:30px;height:30px;border-radius:7px;background:#e9f1f8;color:#426e99}
.auth-card{padding:36px;border:1px solid #d9e1ea;border-radius:12px;background:#fff;box-shadow:0 10px 30px rgba(34,54,76,.06)}.auth-card h1{font-size:26px;line-height:1.3;color:#182b40;margin:0 0 8px}.auth-card>.muted{color:#62758a;line-height:1.6;margin-bottom:26px}.auth-card :deep(.form-stack){gap:18px}.auth-card :deep(.field label){font-size:14px;font-weight:600;color:#293e54}.auth-card :deep(.field input){height:46px;border:1px solid #cbd7e3;border-radius:7px;padding:0 13px;font-size:14px;background:#fff}.auth-card :deep(.field input:focus-visible){outline:2px solid #84a9cc;outline-offset:1px}.auth-card :deep(.wide-button){height:46px;margin-top:4px;border-radius:7px;font-weight:600}.auth-note{margin-top:24px;padding-top:18px;line-height:1.7;color:#64778a}.auth-footer{color:#718093}@media(max-width:500px){.auth-card{padding:26px 22px}}
</style>
