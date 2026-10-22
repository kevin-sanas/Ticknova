<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { useOperation } from '../composables/operation'
import { useUnsavedChanges } from '../composables/unsaved'
import { useSessionStore } from '../stores/session'
import { useDialogFocus } from '../composables/dialogFocus'
import type { Page, User } from '../types'
import { roles } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
const read = useOperation(); const write = useOperation(); const result = ref<Page<User>>({ items: [], total: 0, page: 0, size: 20 }); const page = ref(1)
const availableRoles = ref<string[]>([]); const dialog = ref(false); const selected = ref<User | null>(null); const message = ref('')
const form = reactive({ username: '', displayName: '', password: '', roles: [] as string[], active: true })
const session = useSessionStore()
const dialogFocus = useDialogFocus()
const snapshot = () => ({ ...form, roles: [...form.roles].sort() })
const saved = ref(snapshot())
const { confirmDiscard } = useUnsavedChanges(() => session.authenticated && dialog.value && JSON.stringify(snapshot()) !== JSON.stringify(saved.value))
async function load() { await read.run(async () => { const [users, roleData] = await Promise.all([api.get<Page<User>>(`/users?page=${page.value - 1}&size=20`), api.get<string[]>('/roles')]); result.value = users; availableRoles.value = roleData }) }
onMounted(load)
async function changePage(next: number) { page.value = next; await load() }
function open(user?: User, event?: Event) { if (write.pending.value) return; dialogFocus.capture(event); selected.value = user || null; Object.assign(form, { username: user?.username || '', displayName: user?.displayName || '', password: '', roles: [...user?.roles || []], active: user?.active ?? true }); saved.value = snapshot(); write.error.value = null; message.value = ''; dialog.value = true }
async function create() { if (write.pending.value) return; if (!form.roles.length) { message.value = '请至少选择一个角色。'; return }; await write.run(async () => { await api.post('/users', { username: form.username.trim(), displayName: form.displayName.trim(), password: form.password, roles: form.roles }); form.password = ''; dialog.value = false; await load() }) }
async function saveProfile() { if (write.pending.value || !selected.value) return; const profile = { displayName: form.displayName.trim(), active: form.active }; const originalName = form.displayName; await write.run(async () => { const user = await api.patch<User>(`/users/${selected.value!.id}`, profile); selected.value = user; saved.value = { ...saved.value, displayName: originalName, active: profile.active }; message.value = '基本信息已保存。'; await load() }) }
async function saveRoles() { if (write.pending.value) return; if (!selected.value || !form.roles.length) { message.value = '请至少保留一个角色。'; return }; const roles = [...form.roles]; await write.run(async () => { await api.put(`/users/${selected.value!.id}/roles`, { roles }); saved.value = { ...saved.value, roles: roles.sort() }; message.value = '角色已保存，服务端立即生效。重新登录可刷新页面菜单。'; await load() }) }
async function beforeClose(done: () => void) { if (!write.pending.value && await confirmDiscard()) { form.password = ''; done() } }
async function closeDialog() { await beforeClose(() => { dialog.value = false }) }
</script>
<template>
  <div class="page-heading"><div><h1>用户与角色</h1><p class="muted">管理账号状态与显式角色，按需分配工作权限。</p></div><div class="heading-actions"><el-button type="primary" @click="open(undefined, $event)">＋ 创建用户</el-button></div></div>
  <ErrorNotice :error="read.error.value" reload @reload="load" />
  <section class="panel" :aria-busy="read.pending.value"><div class="panel-header"><h2>用户列表 <span class="small muted">{{ result.total }} 人</span></h2><el-button text :loading="read.pending.value" @click="load">刷新</el-button></div>
    <div v-if="read.pending.value" class="loading-panel" role="status">正在加载用户…</div>
    <template v-else><div class="table-wrap desktop-table"><table><thead><tr><th scope="col">用户</th><th scope="col">角色</th><th scope="col">账号状态</th><th scope="col">密码状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="user in result.items" :key="user.id"><td><strong>{{ user.displayName }}</strong><span class="ticket-number">{{ user.username }}</span></td><td><div class="tag-list"><span v-for="role in user.roles" :key="role" class="tag">{{ roles[role] || role }}</span></div></td><td><span class="badge" :class="user.active ? 'resolved' : 'closed'">{{ user.active ? '启用' : '停用' }}</span></td><td>{{ user.mustChangePassword ? '待首次修改' : '已设置' }}</td><td><el-button text type="primary" @click="open(user, $event)">管理</el-button></td></tr></tbody></table></div>
      <div class="mobile-cards"><article v-for="user in result.items" :key="user.id" class="ticket-card"><div class="panel-header"><div><strong>{{ user.displayName }}</strong><span class="ticket-number">{{ user.username }}</span></div><span class="badge" :class="user.active ? 'resolved' : 'closed'">{{ user.active ? '启用' : '停用' }}</span></div><div class="tag-list"><span v-for="role in user.roles" :key="role" class="tag">{{ roles[role] || role }}</span></div><el-button text type="primary" style="margin-top:10px" @click="open(user, $event)">管理用户</el-button></article></div>
      <div v-if="!result.items.length && !read.error.value" class="empty-state">暂无用户。</div></template>
    <div class="pagination"><span>共 {{ result.total }} 人</span><el-pagination :current-page="page" :page-size="20" :total="result.total" :pager-count="5" layout="prev, pager, next" @current-change="changePage" /></div>
  </section>
  <el-dialog v-model="dialog" :title="selected ? `管理用户 · ${selected.username}` : '创建用户'" width="520px" :close-on-click-modal="false" :before-close="beforeClose" @closed="dialogFocus.restore">
    <div class="dialog-form"><ErrorNotice :error="write.error.value" /><p v-if="message" class="info-notice" role="status">{{ message }}</p>
      <form class="form-stack" @submit.prevent="selected ? saveProfile() : create()">
        <div v-if="!selected" class="field"><label for="new-username">用户名</label><input id="new-username" v-model="form.username" :disabled="write.pending.value" minlength="3" maxlength="80" pattern="[A-Za-z0-9][A-Za-z0-9._-]{2,79}" autocomplete="off" required placeholder="用于登录，不可重复" /></div>
        <div class="field"><label for="user-name">显示名称</label><input id="user-name" v-model="form.displayName" :disabled="write.pending.value" maxlength="120" required /></div>
        <div v-if="!selected" class="field"><label for="initial-password">初始密码</label><input id="initial-password" v-model="form.password" :disabled="write.pending.value" type="password" minlength="12" maxlength="72" autocomplete="new-password" required /><small>至少 12 个字符，UTF-8 长度最多 72 字节。用户首次登录必须修改。</small></div>
        <label v-if="selected" class="checkbox-label"><input v-model="form.active" type="checkbox" :disabled="write.pending.value" />允许此账号登录</label>
        <fieldset v-if="!selected" class="field" style="border:0;padding:0;margin:0"><legend>角色（至少一个）</legend><div class="checkbox-group"><label v-for="role in availableRoles" :key="role" class="checkbox-label"><input v-model="form.roles" type="checkbox" :disabled="write.pending.value" :value="role" />{{ roles[role] || role }}</label></div></fieldset>
        <div class="form-actions"><el-button type="primary" native-type="submit" :loading="write.pending.value" :disabled="write.pending.value">{{ selected ? '保存基本信息' : '创建用户' }}</el-button><el-button :disabled="write.pending.value" @click="closeDialog">关闭</el-button></div>
      </form>
      <form v-if="selected" class="settings-form" style="margin-top:24px;padding-top:22px;border-top:1px solid var(--color-border)" @submit.prevent="saveRoles"><fieldset class="field" style="border:0;padding:0;margin:0"><legend>角色配置（至少一个）</legend><div class="checkbox-group"><label v-for="role in availableRoles" :key="role" class="checkbox-label"><input v-model="form.roles" type="checkbox" :disabled="write.pending.value" :value="role" />{{ roles[role] || role }}</label></div><small>角色之间不隐式继承；队列处理权限需在队列管理中单独配置。</small></fieldset><el-button native-type="submit" :loading="write.pending.value" :disabled="write.pending.value">保存角色</el-button></form>
    </div>
  </el-dialog>
</template>
