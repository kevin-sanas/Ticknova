<script setup lang="ts">
import type { Component } from 'vue'
import { Search, Tickets, Reading, MagicStick, User, Grid, DataAnalysis, Clock, Plus, Close, Right, Back, ArrowRight, ArrowDown, Document, Folder, Setting, Upload, Edit, Check, Lock, ChatDotRound, List, Link, RefreshLeft, RefreshRight, Minus } from '@element-plus/icons-vue'
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 18 })
const icons: Record<string, Component> = {
  search: Search, inbox: Tickets, book: Reading, ai: MagicStick, users: User,
  grid: Grid, chart: DataAnalysis, history: Clock, plus: Plus, close: Close,
  arrow: Right, back: Back, chevron: ArrowRight, down: ArrowDown, file: Document,
  folder: Folder, settings: Setting, upload: Upload, edit: Edit, check: Check,
  lock: Lock, message: ChatDotRound, bulletList: List, orderedList: List,
  link: Link, undo: RefreshLeft, redo: RefreshRight, rule: Minus,
}
const formatting: Record<string, string> = { bold: 'B', italic: 'I', underline: 'U', strike: 'S', code: '</>', codeBlock: '</>', quote: '❝' }
</script>
<template>
  <span v-if="formatting[name]" class="format-icon" :class="`format-icon--${name}`" :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.max(11, size - 3)}px` }" aria-hidden="true">{{ formatting[name] }}</span>
  <component :is="icons[name] || Grid" v-else class="app-icon" :style="{ width: `${size}px`, height: `${size}px` }" aria-hidden="true" />
</template>
<style scoped>
.format-icon{display:inline-flex;align-items:center;justify-content:center;font-weight:700;line-height:1}.format-icon--italic{font-style:italic}.format-icon--underline{text-decoration:underline}.format-icon--strike{text-decoration:line-through}.format-icon--code,.format-icon--codeBlock{font-size:10px!important}
</style>
