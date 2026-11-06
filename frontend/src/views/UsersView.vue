<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api, queryString } from '../api'
import { useOperation } from '../composables/operation'
import { useUnsavedChanges } from '../composables/unsaved'
import { useSessionStore } from '../stores/session'
import { useDialogFocus } from '../composables/dialogFocus'
import type { Page, User } from '../types'
import { roles } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
import AppIcon from '../components/AppIcon.vue'
const read = useOperation(); const write = useOperation(); const result = ref<Page<User>>({ items: [], total: 0, page: 0, size: 20 }); const page = ref(1)
const query = ref(''); const status = ref(''); const roleFilter = ref(''); const applied = ref({ q: '', active: '', role: '' })
const availableRoles = ref<string[]>([]); const dialog = ref(false); const selected = ref<User | null>(null); const message = ref('')
const form = reactive({ username: '', displayName: '', password: '', roles: [] as string[], active: true })
const session = useSessionStore()
const dialogFocus = useDialogFocus()
const snapshot = () => ({ ...form, roles: [...form.roles].sort() })
const saved = ref(snapshot())
const { confirmDiscard } = useUnsavedChanges(() => session.authenticated && dialog.value && JSON.stringify(snapshot()) !== JSON.stringify(saved.value))
async function load() { await read.run(async () => { const [users, roleData] = await Promise.all([api.get<Page<User>>(`/users?${queryString({ page: page.value - 1, size: 20, ...applied.value })}`), api.get<string[]>('/roles')]); result.value = users; availableRoles.value = roleData }) }
onMounted(load)
async function search() { if (read.pending.value) return; page.value = 1; applied.value = { q: query.value.trim(), active: status.value, role: roleFilter.value }; await load() }
async function reset() { if (read.pending.value) return; query.value = ''; status.value = ''; roleFilter.value = ''; await search() }
async function changePage(next: number) { if (read.pending.value) return; page.value = next; await load() }
function open(user?: User, event?: Event) { if (write.pending.value) return; dialogFocus.capture(event); selected.value = user || null; Object.assign(form, { username: user?.username || '', displayName: user?.displayName || '', password: '', roles: [...user?.roles || []], active: user?.active ?? true }); saved.value = snapshot(); write.error.value = null; message.value = ''; dialog.value = true }
async function create() { if (write.pending.value) return; if (!form.roles.length) { message.value = '请至少选择一个角色。'; return }; await write.run(async () => { await api.post('/users', { username: form.username.trim(), displayName: form.displayName.trim(), password: form.password, roles: form.roles }); form.password = ''; dialog.value = false; await load() }) }
async function saveProfile() { if (write.pending.value || !selected.value) return; const profile = { displayName: form.displayName.trim(), active: form.active }; const originalName = form.displayName; await write.run(async () => { const user = await api.patch<User>(`/users/${selected.value!.id}`, profile); selected.value = user; saved.value = { ...saved.value, displayName: originalName, active: profile.active }; message.value = '基本信息已保存。'; await load() }) }
async function saveRoles() { if (write.pending.value) return; if (!selected.value || !form.roles.length) { message.value = '请至少保留一个角色。'; return }; const roles = [...form.roles]; await write.run(async () => { await api.put(`/users/${selected.value!.id}/roles`, { roles }); saved.value = { ...saved.value, roles: roles.sort() }; message.value = '角色已保存，服务端立即生效。重新登录可刷新页面菜单。'; await load() }) }
async function beforeClose(done: () => void) { if (!write.pending.value && await confirmDiscard()) { form.password = ''; done() } }
async function closeDialog() { await beforeClose(() => { dialog.value = false }) }
</script>
<template>
  <div class="page-heading"><div><h1>用户与角色</h1><p class="muted">管理账号状态与显式角色，按需分配工作权限。</p></div><div class="heading-actions"><el-button type="primary" @click="open(undefined, $event)"><AppIcon name="plus" :size="16" />创建用户</el-button></div></div>
  <ErrorNotice :error="read.error.value" reload @reload="load" />
  <section class="panel" :aria-busy="read.pending.value"><div class="panel-header"><h2>用户列表 <span class="small muted">{{ result.total }} 人</span></h2><el-button :loading="read.pending.value" @click="load"><AppIcon name="redo" :size="15" />刷新</el-button></div>
    <form id="user-filter-form" class="filter-form management-filter-form" @submit.prevent="search"><div class="field grow"><label class="sr-only" for="user-query">搜索用户</label><div class="user-search"><AppIcon name="search" :size="16" /><input id="user-query" v-model="query" type="search" maxlength="120" placeholder="搜索用户名或显示名称" /></div></div><div class="field"><label class="sr-only" for="user-status">账号状态</label><el-select id="user-status" v-model="status" class="user-status-filter" placeholder="全部状态"><el-option label="全部状态" value="" /><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select></div><div class="field"><label class="sr-only" for="user-role">角色</label><el-select id="user-role" v-model="roleFilter" class="user-role-filter" clearable placeholder="全部角色"><el-option v-for="role in availableRoles" :key="role" :label="roles[role] || role" :value="role" /></el-select></div><div class="filter-actions"><el-button type="primary" native-type="submit" :loading="read.pending.value">查询</el-button><el-button :disabled="read.pending.value" @click="reset">重置</el-button></div></form>
    <div v-if="read.pending.value" class="loading-panel" role="status">正在加载用户…</div>
    <template v-else><div class="table-wrap desktop-table"><table><thead><tr><th scope="col">用户</th><th scope="col">角色</th><th scope="col">账号状态</th><th scope="col">密码状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="user in result.items" :key="user.id"><td><strong>{{ user.displayName }}</strong><span class="ticket-number">{{ user.username }}</span></td><td><div class="tag-list"><span v-for="role in user.roles" :key="role" class="tag">{{ roles[role] || role }}</span></div></td><td><span class="badge" :class="user.active ? 'resolved' : 'closed'">{{ user.active ? '启用' : '停用' }}</span></td><td>{{ user.mustChangePassword ? '待首次修改' : '已设置' }}</td><td><el-button @click="open(user, $event)"><AppIcon name="settings" :size="15" />管理</el-button></td></tr></tbody></table></div>
      <div class="mobile-cards"><article v-for="user in result.items" :key="user.id" class="ticket-card"><div class="panel-header"><div><strong>{{ user.displayName }}</strong><span class="ticket-number">{{ user.username }}</span></div><span class="badge" :class="user.active ? 'resolved' : 'closed'">{{ user.active ? '启用' : '停用' }}</span></div><div class="tag-list"><span v-for="role in user.roles" :key="role" class="tag">{{ roles[role] || role }}</span></div><p class="small muted user-password-status">密码状态：{{ user.mustChangePassword ? '待首次修改' : '已设置' }}</p><el-button text type="primary" style="margin-top:10px" @click="open(user, $event)">管理用户</el-button></article></div>
      <div v-if="!result.items.length && !read.error.value" class="empty-state">{{ applied.q || applied.active || applied.role ? '没有符合条件的用户。' : '暂无用户。' }}</div></template>
    <div class="pagination"><span>共 {{ result.total }} 人</span><el-pagination :current-page="page" :page-size="20" :total="result.total" :pager-count="5" :disabled="read.pending.value" layout="prev, pager, next" @current-change="changePage" /></div>
  </section>
  <el-dialog v-model="dialog" :title="selected ? `管理用户 · ${selected.username}` : '创建用户'" width="520px" :close-on-click-modal="false" :before-close="beforeClose" @closed="dialogFocus.restore">
    <div class="dialog-form"><ErrorNotice :error="write.error.value" /><p v-if="message" class="info-notice" role="status">{{ message }}</p>
      <form class="form-stack" @submit.prevent="selected ? saveProfile() : create()">
        <div v-if="selected" class="user-dialog-section-heading"><h3>基本信息</h3><p>设置用户的显示名称和登录状态。</p></div>
        <div v-if="!selected" class="field"><label for="new-username">用户名</label><input id="new-username" v-model="form.username" :disabled="write.pending.value" minlength="3" maxlength="80" pattern="[A-Za-z0-9][A-Za-z0-9._-]{2,79}" autocomplete="off" required placeholder="用于登录，不可重复" /></div>
        <div class="field"><label for="user-name">显示名称</label><input id="user-name" v-model="form.displayName" :disabled="write.pending.value" maxlength="120" required /></div>
        <div v-if="!selected" class="field"><label for="initial-password">初始密码</label><input id="initial-password" v-model="form.password" :disabled="write.pending.value" type="password" minlength="12" maxlength="72" autocomplete="new-password" required /><small>至少 12 个字符，UTF-8 长度最多 72 字节。用户首次登录必须修改。</small></div>
        <label v-if="selected" class="checkbox-label user-active-control"><input v-model="form.active" type="checkbox" :disabled="write.pending.value" /><span><strong>允许此账号登录</strong><small>关闭后，此账号将无法继续访问系统。</small></span></label>
        <fieldset v-if="!selected" class="field" style="border:0;padding:0;margin:0"><legend>角色（至少一个）</legend><div class="checkbox-group"><label v-for="role in availableRoles" :key="role" class="checkbox-label"><input v-model="form.roles" type="checkbox" :disabled="write.pending.value" :value="role" />{{ roles[role] || role }}</label></div></fieldset>
        <div class="form-actions"><el-button :disabled="write.pending.value" @click="closeDialog">取消</el-button><el-button type="primary" native-type="submit" :loading="write.pending.value" :disabled="write.pending.value">{{ selected ? '保存基本信息' : '创建用户' }}</el-button></div>
      </form>
      <form v-if="selected" class="settings-form user-roles-form" @submit.prevent="saveRoles"><fieldset class="field user-role-field"><legend>角色配置 <span>（至少一个）</span></legend><small>角色之间不隐式继承；队列处理权限需在队列管理中单独配置。</small><div class="checkbox-group user-role-options"><label v-for="role in availableRoles" :key="role" class="checkbox-label user-role-option"><input v-model="form.roles" type="checkbox" :disabled="write.pending.value" :value="role" />{{ roles[role] || role }}</label></div></fieldset><div class="form-actions"><el-button native-type="submit" :loading="write.pending.value" :disabled="write.pending.value">保存角色</el-button></div></form>
    </div>
  </el-dialog>
</template>
<style scoped>
.management-filter-form { margin-bottom: var(--space-5); }
.user-status-filter, .user-role-filter { width: 100%; }
.page-heading{margin-bottom:22px}.page-heading h1{font-size:26px;color:#1d3045}.page-heading p{margin-top:6px;color:#64778a}.panel{padding:20px;border:1px solid #dbe2e9;border-radius:10px;background:#fff}.panel-header{gap:12px;margin-bottom:18px}.panel-header h2{font-size:16px;color:#20364d}.management-filter-form{padding:14px;background:#f7f9fb;border:1px solid #e1e8ef;border-radius:8px;gap:12px}.management-filter-form .field label{font-size:12px;color:#5b6e81}.management-filter-form .field input{height:40px;border-color:#ccd8e3;border-radius:7px}.management-filter-form :deep(.el-select__wrapper){min-height:40px}.desktop-table table{width:100%;border-collapse:collapse}.desktop-table th{background:#f6f8fa;color:#627388;font-size:12px;font-weight:600}.desktop-table th,.desktop-table td{padding:13px 14px;border-bottom:1px solid #e7edf2;text-align:left}.desktop-table tbody tr:hover{background:#f8fafc}.desktop-table td strong{color:#223b54}.desktop-table :deep(.el-button.is-text){border:1px solid #d2dee9;border-radius:6px;padding:7px 12px}.pagination{padding-top:16px}.ticket-number{color:#708194}.tag{background:#ecf3fa;color:#355d83;border-radius:5px}.ticket-card{border-color:#dce5ed;border-radius:8px}
.heading-actions :deep(.el-button>span),.panel-header :deep(.el-button>span),.desktop-table :deep(.el-button>span){display:inline-flex;align-items:center;gap:6px}.management-filter-form{margin:0 -20px;padding:12px 16px;background:#fff;border:solid #e7edf2;border-width:1px 0;border-radius:0;gap:9px}.management-filter-form .field{gap:0}.management-filter-form :deep(.el-select__wrapper){min-height:36px}.user-search{position:relative;display:flex;align-items:center}.user-search .app-icon{position:absolute;left:11px;color:#718599;pointer-events:none}.user-search input{width:100%;height:36px;border:1px solid #cbd6e0;border-radius:6px;background:#fff;color:#243b53;font:inherit;font-size:13px;padding:0 10px 0 34px}.user-search input:focus-visible{outline:2px solid #84a9cc;outline-offset:1px}.desktop-table{margin:0 -20px}.desktop-table th,.desktop-table td{padding:9px 16px}.desktop-table th{padding-block:10px}.desktop-table :deep(.el-button){height:34px;border-color:#d3dee9;color:#355b7f}.dialog-form .form-stack{gap:15px}.dialog-form .field{gap:6px}.dialog-form .field input:not([type=checkbox]){height:38px;border:1px solid #ccd8e3;border-radius:6px;padding:0 11px}.dialog-form .field small{font-size:12px;line-height:1.5}.dialog-form .form-actions{display:flex;justify-content:flex-end;gap:8px}.dialog-form .form-actions :deep(.el-button+.el-button){margin-left:0}.dialog-form .checkbox-group{gap:10px 14px}
@media(max-width:640px){.panel{padding:15px}.management-filter-form{margin-inline:-15px;padding:12px}.management-filter-form .field.grow{flex:1 1 100%;min-width:0}.user-search{width:100%}.management-filter-form .field:not(.grow){flex:1 1 120px;min-width:0}.desktop-table{display:none}.mobile-cards{display:grid;gap:10px;padding-top:12px}.page-heading h1{font-size:23px}}
.user-password-status{margin:8px 0 0}

.user-dialog-section-heading h3{margin:0;color:var(--color-text);font-size:var(--text-body);line-height:1.4}
.user-dialog-section-heading p{margin:5px 0 0;color:var(--color-text-secondary);font-size:var(--text-xs);line-height:1.5}
.user-active-control{display:flex;align-items:flex-start;gap:10px;padding:12px;border:1px solid var(--color-border);border-radius:var(--radius-control);background:var(--color-surface-muted)}
.user-active-control input{margin-top:3px}.user-active-control span{display:grid;gap:3px}.user-active-control strong{font-size:var(--text-sm)}.user-active-control small{font-size:var(--text-xs);line-height:1.5;color:var(--color-text-secondary)}
.user-roles-form{margin-top:20px;padding-top:20px;border-top:1px solid var(--color-border);gap:10px}
.user-role-field{border:0;padding:0;margin:0;min-width:0}.user-role-field legend{font-size:var(--text-body);font-weight:600;color:var(--color-text)}.user-role-field legend span{font-size:var(--text-xs);font-weight:400;color:var(--color-text-secondary)}
.user-role-field>small{color:var(--color-text-secondary);line-height:1.5}.user-role-options{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin-top:4px}
.user-role-option{min-height:42px;padding:9px 10px;border:1px solid var(--color-border);border-radius:var(--radius-control);background:var(--color-surface);gap:8px}
.user-role-option:focus-within{outline:2px solid var(--color-focus);outline-offset:1px}.user-roles-form .form-actions{justify-content:flex-end}
@media(max-width:420px){.user-role-options{grid-template-columns:1fr}}
</style>
