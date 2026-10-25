import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { DESIGN_TOKENS, hexToCssRgb } from './Design-Tokens.mjs'

function token(source, name) {
  return source.match(new RegExp(`--${name}:\\s*(#[0-9a-f]{6})`, 'i'))?.[1]?.toLowerCase()
}

test('browser design contract matches the committed Ticknova color tokens', async () => {
  const source = await readFile(new URL('../frontend/src/tokens.css', import.meta.url), 'utf8')
  assert.equal(token(source, 'color-primary'), DESIGN_TOKENS.primary)
  assert.equal(token(source, 'color-canvas'), DESIGN_TOKENS.canvas)
  assert.equal(hexToCssRgb(DESIGN_TOKENS.canvas), 'rgb(245, 247, 244)')
})

test('hexToCssRgb rejects malformed design token values', () => {
  assert.throws(() => hexToCssRgb('#fff'), /six-digit CSS hex/)
})

test('analytics browser check targets the chart root without matching ECharts ARIA descendants', async () => {
  const source = await readFile(new URL('./Browser-Design.mjs', import.meta.url), 'utf8')
  assert.match(source, /\.daily-chart\[role="img"\]/)
  assert.doesNotMatch(source, /\.daily-panel \[role="img"\]/)
})

test('disabled reviewer session is verified through the real review queue', async () => {
  const source = await readFile(new URL('./Browser-Design.mjs', import.meta.url), 'utf8')
  assert.match(source, /\/api\/v1\/knowledge\/review-tasks/)
  assert.match(source, /name: '刷新待办'/)
  assert.doesNotMatch(source, /DISABLED_FIXTURE_REFRESH[\s\S]{0,500}刷新空间/)
})

test('knowledge browser regression uses the accessible archive menu', async () => {
  const source = await readFile(new URL('./Browser-Knowledge.mjs', import.meta.url), 'utf8')
  assert.match(source, /name: '更多文档操作'/)
  assert.match(source, /getByRole\('menuitem', \{ name: '归档文档'/)
  assert.doesNotMatch(source, /getByRole\('button', \{ name: '归档文档'/)
})
