/** Read bounded SSE responses without treating a disconnected stream as success. */
export async function readSearchResponse(response, responseJson) {
  if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream')) return responseJson(response)
  if (!response.body) throw new Error('搜索连接已中断，请重试。')
  const reader = response.body.getReader(), decoder = new TextDecoder()
  let buffer = '', received = 0
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (value) {
        received += value.byteLength
        if (received > 2 * 1024 * 1024 + 65536) throw new Error('搜索结果过大，请缩小查询范围。')
        buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')
      }
      let boundary
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2)
        const lines = frame.split('\n')
        const event = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
        if (event !== 'result' && event !== 'error') continue
        const raw = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
        const data = JSON.parse(raw)
        if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('搜索响应无效，请重试。')
        if (event === 'error') return responseJson({ ok: false, json: async () => data })
        return data
      }
      if (done) throw new Error('搜索连接已中断，请重试。')
    }
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
}
