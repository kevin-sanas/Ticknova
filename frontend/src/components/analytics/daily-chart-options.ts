import type { WorkspaceAnalytics } from '../../workspace-types'
export interface ChartColors { primary: string; secondary: string; text: string; muted: string; border: string }
export function dailyChartOption(daily: WorkspaceAnalytics['daily'], colors: ChartColors, reducedMotion: boolean) {
  return {
    animation: !reducedMotion,
    aria: { enabled: true, decal: { show: true }, description: '每日工单数量，单位张，UTC。新建与解决按日期并列显示；精确值见下方每日数据表。' },
    tooltip: { trigger: 'axis' as const, renderMode: 'richText' as const, axisPointer: { type: 'shadow' as const } },
    legend: { data: ['新建', '解决'], top: 0, textStyle: { color: colors.text } },
    grid: { left: 48, right: 16, top: 48, bottom: 40, containLabel: true },
    xAxis: { type: 'category' as const, data: daily.map(day => day.date), axisLabel: { color: colors.muted, formatter: (value: string) => value.slice(5), hideOverlap: true }, axisLine: { lineStyle: { color: colors.border } }, axisTick: { show: false } },
    yAxis: { type: 'value' as const, name: '张', minInterval: 1, nameTextStyle: { color: colors.muted }, axisLabel: { color: colors.muted }, splitLine: { lineStyle: { color: colors.border } } },
    series: [{ name: '新建', type: 'bar' as const, data: daily.map(day => day.created), barMaxWidth: 20, itemStyle: { color: colors.primary } }, { name: '解决', type: 'bar' as const, data: daily.map(day => day.resolved), barMaxWidth: 20, itemStyle: { color: colors.secondary } }],
  }
}
