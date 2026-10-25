<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { use } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import type { WorkspaceAnalytics } from '../../workspace-types'
import { dailyChartOption, type ChartColors } from './daily-chart-options'
use([BarChart, AriaComponent, GridComponent, LegendComponent, TooltipComponent, SVGRenderer])
const props = defineProps<{ daily: WorkspaceAnalytics['daily'] }>()
const colors = shallowRef<ChartColors | null>(null), reducedMotion = ref(true)
let media: MediaQueryList | undefined
const option = computed(() => colors.value ? dailyChartOption(props.daily, colors.value, reducedMotion.value) : {})
function updateMotion() { reducedMotion.value = media?.matches ?? true }
onMounted(() => {
  const style = getComputedStyle(document.documentElement), token = (name: string) => style.getPropertyValue(name).trim()
  colors.value = { primary: token('--color-primary'), secondary: token('--color-ai'), text: token('--color-text'), muted: token('--color-text-secondary'), border: token('--color-border') }
  media = window.matchMedia?.('(prefers-reduced-motion: reduce)'); updateMotion(); media?.addEventListener('change', updateMotion)
})
onBeforeUnmount(() => media?.removeEventListener('change', updateMotion))
</script>
<template><div class="daily-chart" role="img" aria-label="每日新建与解决柱状图，单位张，UTC；详细值可在下方每日数据表查看。"><VChart v-if="colors" :option="option" :init-options="{ renderer: 'svg' }" autoresize /></div></template>
<style scoped>
.daily-chart{height:320px;min-width:0;width:100%}@media(max-width:600px){.daily-chart{height:260px}}
</style>
