<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
interface NavigationItem { path: string; label: string; icon: string }
const props = defineProps<{ groups?: { label: string; items: NavigationItem[] }[]; items?: NavigationItem[]; collapsed?: boolean }>()
const navigationGroups = computed(() => (props.groups || [{ label: '', items: props.items || [] }]).filter(group => group.items.length))
const allItems = computed(() => navigationGroups.value.flatMap(group => group.items))
defineEmits<{ navigate: [] }>()
const route = useRoute()
function active(path: string) {
  const matches = allItems.value.filter(item => route.path === item.path || route.path.startsWith(`${item.path}/`))
  return matches.sort((a, b) => b.path.length - a.path.length)[0]?.path === path
}
const narrowViewport = ref(false)
let viewport: MediaQueryList | undefined
function viewportChanged(event: MediaQueryListEvent) { narrowViewport.value = event.matches }
onMounted(() => {
  viewport = window.matchMedia?.('(max-width: 850px)')
  if (!viewport) return
  narrowViewport.value = viewport.matches
  viewport.addEventListener('change', viewportChanged)
})
onBeforeUnmount(() => viewport?.removeEventListener('change', viewportChanged))
const paths: Record<string, string> = {
  search: 'm21 21-5-5 M19 10a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  inbox: 'M3 4h18v16H3z M3 13h5l2 3h4l2-3h5',
  book: 'M12 5v16 M3 3h5l4 2 4-2h5v16h-5l-4 2-4-2H3z',
  ai: 'm12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3z',
  users: 'M16 21v-3a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v3 M9 10a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M22 21v-3a4 4 0 0 0-3-4 M16 2a4 4 0 0 1 0 8',
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  chart: 'M3 3v18h18 M7 15v3 M12 10v8 M17 5v13',
  history: 'M3 11a9 9 0 1 1 3 8 M3 3v8h8 M12 7v5l4 2',
}
</script>
<template>
  <nav aria-label="主导航">
    <div v-for="group in navigationGroups" :key="group.label" class="navigation-group" role="group" :aria-label="group.label || undefined">
    <p v-if="group.label && !collapsed" class="nav-group-title">{{ group.label }}</p>
    <el-tooltip v-for="item in group.items" :key="item.path" :content="item.label" placement="right" popper-class="navigation-tooltip" :show-after="150" :disabled="!collapsed || narrowViewport" :trigger="['hover', 'focus']">
      <router-link :to="item.path" :class="{ active: active(item.path) }" :aria-current="active(item.path) ? 'page' : undefined" :aria-label="collapsed ? item.label : undefined" @click="$emit('navigate')">
        <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path :d="paths[item.icon] || paths.grid" /></svg>
        <span :class="{ 'sr-only': collapsed }">{{ item.label }}</span>
      </router-link>
    </el-tooltip>
    </div>
  </nav>
</template>
<style scoped>
.navigation-group { display: grid; gap: var(--space-1); min-width: 0; }
.navigation-group + .navigation-group { margin-top: var(--space-4); }
.nav-group-title { margin: 0; padding: var(--space-2) var(--space-3); color: var(--color-text-secondary); font-size: var(--text-xs); font-weight: 600; }
</style>
