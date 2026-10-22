import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '../api'
import type { LoginResult, User } from '../types'
export const useSessionStore = defineStore('session', () => {
  const token = ref(''); const user = ref<User | null>(null); const expiresAt = ref(0); const notice = ref('')
  const authenticated = computed(() => !!token.value && !!user.value && expiresAt.value > Date.now())
  function accept(result: LoginResult) { token.value = result.accessToken; user.value = result.user; expiresAt.value = Date.now() + result.expiresIn * 1000; notice.value = '' }
  function clear(message = '') { token.value = ''; user.value = null; expiresAt.value = 0; notice.value = message }
  async function login(username: string, password: string) { const result = await api.post<LoginResult>('/auth/login', { username, password }, { anonymous: true }); accept(result); return result.user }
  async function logout() { const originalToken = token.value; try { await api.post('/auth/logout') } finally { if (token.value === originalToken) clear() } }
  return { token, user, expiresAt, notice, authenticated, accept, clear, login, logout }
})
