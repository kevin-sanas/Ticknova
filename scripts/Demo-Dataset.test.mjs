import { test } from 'node:test'
import assert from 'node:assert/strict'
import { queues, services, documents, tickets, richContent } from './Demo-Dataset.mjs'

test('showcase stays within promised scale and covers workflow states and catalog relationships', () => {
  assert.equal(queues.length, 4); assert.equal(services.length, 8)
  assert.equal(documents.length, 12); assert.equal(tickets.length, 24)
  assert.equal(new Set(tickets.map(row => row.status)).size, 7)
  assert.equal(new Set(documents.map(row => row[1])).size, documents.length)
  for (const ticket of tickets) {
    assert.ok(queues.some(row => row[0] === ticket.queue)); assert.ok(services.some(row => row[0] === ticket.service))
  }
  for (const document of documents) {
    assert.ok(document[0].length <= 4); assert.equal(richContent(document).content.length, 4)
    assert.doesNotMatch(document.join(' '), /V2合成|浏览器验收|验收审核人|歌词|时光机/)
  }
})
test('common demo questions have explicit original evidence', () => {
  for (const question of ['网络无法连接怎么办','VPN 连不上','怎么重置密码']) assert.ok(documents.some(row => row.slice(2).join('').includes(question)))
})
