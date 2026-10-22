<script setup lang="ts">
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { destinationFor } from '../access'
import { useOperation } from '../composables/operation'
import ErrorNotice from '../components/ErrorNotice.vue'
const session = useSessionStore(); const router = useRouter(); const operation = useOperation()
const form = reactive({ username: '', password: '' })
async function submit() { await operation.run(async () => { const user = await session.login(form.username.trim(), form.password); form.password = ''; await router.replace(destinationFor(user)) }) }
</script>
<template>
  <div class="auth-brand"><span class="brand-mark" aria-hidden="true">t</span>Ticknova</div>
  <section class="auth-card" aria-labelledby="login-heading">
    <h1 id="login-heading">欢迎回来</h1><p class="muted">登录账号，继续处理你的工作。</p>
    <div v-if="session.notice" class="info-notice" role="status">{{ session.notice }}</div>
    <ErrorNotice :error="operation.error.value" login />
    <form class="form-stack" @submit.prevent="submit">
      <div class="field"><label for="username">用户名</label><input id="username" v-model="form.username" name="username" autocomplete="username" maxlength="80" required placeholder="输入用户名" autofocus /></div>
      <div class="field"><label for="password">密码</label><input id="password" v-model="form.password" name="password" type="password" autocomplete="current-password" required placeholder="输入密码" /></div>
      <el-button type="primary" native-type="submit" :loading="operation.pending.value" class="wide-button">登录工作台 <span aria-hidden="true">→</span></el-button>
    </form>
    <p class="auth-note">账号由管理员创建。首次登录需要修改初始密码。</p>
  </section>
  <p class="auth-footer">会话仅在当前页面有效，刷新后请重新登录。</p>
</template>
