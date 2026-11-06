<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from './AppIcon.vue'
interface NavigationItem { path: string; label: string; icon: string }
interface NavigationGroup { label: string; icon?: string; items: NavigationItem[] }
const props = defineProps<{ groups?: NavigationGroup[]; items?: NavigationItem[]; collapsed?: boolean }>()
const navigationGroups = computed(() => (props.groups || [{ label: '', items: props.items || [] }]).filter(group => group.items.length))
const allItems = computed(() => navigationGroups.value.flatMap(group => group.items))
defineEmits<{ navigate: [] }>()
const route = useRoute()
const navId = useId()
const expanded = ref<string[]>([])
function active(path: string) {
  const matches = allItems.value.filter(item => route.path === item.path || route.path.startsWith(`${item.path}/`))
  return matches.sort((a, b) => b.path.length - a.path.length)[0]?.path === path
}
function toggle(label: string) {
  expanded.value = expanded.value.includes(label) ? expanded.value.filter(item => item !== label) : [...expanded.value, label]
}
watch(() => [route.path, navigationGroups.value] as const, () => {
  const group = navigationGroups.value.find(item => item.items.some(link => active(link.path)))
  if (group && !expanded.value.includes(group.label)) expanded.value.push(group.label)
}, { immediate: true })
</script>
<template>
  <nav aria-label="主导航" class="nav-drawers">
    <div v-for="(group, index) in navigationGroups" :key="group.label" class="navigation-group">
      <button v-if="group.label" class="group-toggle" type="button" :aria-expanded="expanded.includes(group.label)" :aria-controls="`${navId}-group-${index}`" @click="toggle(group.label)">
        <AppIcon :name="group.icon || 'grid'" :size="17" /><span>{{ group.label }}</span><AppIcon class="group-chevron" name="down" :size="14" />
      </button>
      <div :id="`${navId}-group-${index}`" v-show="!group.label || expanded.includes(group.label)" class="group-items">
        <router-link v-for="item in group.items" :key="item.path" :to="item.path" :class="{ active: active(item.path) }" :aria-current="active(item.path) ? 'page' : undefined" @click="$emit('navigate')"><AppIcon :name="item.icon" :size="16" /><span>{{ item.label }}</span></router-link>
      </div>
    </div>
  </nav>
</template>
<style scoped>
.nav-drawers{display:grid;gap:5px;align-content:start}.navigation-group{min-width:0}.group-toggle{width:100%;display:flex;align-items:center;gap:10px;min-height:40px;padding:0 11px;border:0;border-radius:7px;color:#e7eef7;background:transparent;text-align:left;font:inherit;font-size:14px;font-weight:600;cursor:pointer}.group-toggle:hover,.group-toggle[aria-expanded=true]{background:#435e7b}.group-toggle:focus-visible{outline:2px solid #c9dcf1;outline-offset:2px}.group-chevron{margin-left:auto;transition:transform .2s}.group-toggle[aria-expanded=true] .group-chevron{transform:rotate(180deg)}.group-items{padding:4px 0 5px 27px}.group-items :deep(a){min-height:33px;padding:7px 11px;color:#d7e2ee;font-size:13px;border-radius:6px}.group-items :deep(a:hover){background:#405a77;color:#fff}.group-items :deep(a.active){background:#607e9f;color:#fff;font-weight:600}.navigation-drawer .group-toggle{color:#293f59}.navigation-drawer .group-toggle:hover,.navigation-drawer .group-toggle[aria-expanded=true]{background:#e6edf5}.navigation-drawer .group-items :deep(a){color:#40556b}.navigation-drawer .group-items :deep(a.active){background:#dce9f8;color:#234e7e}@media(prefers-reduced-motion:reduce){.group-chevron{transition:none}}

.group-items :deep(a){display:flex;align-items:center;gap:10px;min-width:0}.group-items :deep(a .app-icon){flex:none}.group-items :deep(a span){min-width:0;overflow-wrap:anywhere}
</style>
