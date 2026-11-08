import test from 'node:test'
import assert from 'node:assert/strict'
import { readSearchResponse } from '../src/search-response.js'

const jsonResponse = async r => { const data = await r.json(); if (!r.ok) throw new Error(data.code || data.detail); return data }
function streaming(text, split = 3) {
  const bytes = new TextEncoder().encode(text)
  return new Response(new ReadableStream({ start(controller) { for (let i=0;i<bytes.length;i+=split) controller.enqueue(bytes.slice(i,i+split)); controller.close() } }), { headers: { 'content-type': 'text/event-stream' } })
}
test('heartbeats and UTF-8 split chunks preserve the terminal result', async () => {
  const expected = { status: 'ok', results: [], query: '查找车辆' }
  const result = await readSearchResponse(streaming(`event: pending\ndata: {}\n\n: waiting\n\nevent: result\ndata: ${JSON.stringify(expected)}\n\n`), jsonResponse)
  assert.deepEqual(result, expected)
})
test('terminal errors remain errors and interrupted streams cannot appear successful', async () => {
  await assert.rejects(readSearchResponse(streaming('event: error\ndata: {"code":"model_unavailable"}\n\n'), jsonResponse), /model_unavailable/)
  await assert.rejects(readSearchResponse(streaming('event: pending\ndata: {}\n\n'), jsonResponse), /搜索连接已中断/)
})
test('ordinary JSON API responses stay compatible', async () => {
  assert.deepEqual(await readSearchResponse(Response.json({ status: 'ok' }), jsonResponse), { status: 'ok' })
})
