export function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? '' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}

export function uncertainLabels(fields) {
  const labels = { upper_color: '上衣颜色', lower_color: '下装颜色', vehicle_color: '车身颜色', carrying: '携带物', visible_action: '可见动作', transport_type: '交通工具', bbox_xyxy: '目标位置', object_type: '目标类别' }
  return [...new Set((Array.isArray(fields) ? fields : []).filter(x => typeof x === 'string' && x.trim()).map(x => labels[x] || '其他特征'))]
}

export function formatOffset(value) {
  if (!Number.isFinite(value) || value < 0) return '位置未知'
  const seconds = Math.floor(value / 1000)
  return `${String(Math.floor(seconds / 3600)).padStart(2, '0')}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

export function evidenceBox(detail, naturalSize, digestState) {
  const location = detail?.target_location, evidence = detail?.evidence, box = location?.bbox_xyxy
  if (digestState !== 'valid' || evidence?.capture_integrity !== 'matched' || location?.status !== 'bound_detection' || location.coordinate_space !== 'jpeg_pixels') return null
  if (!naturalSize || naturalSize.width !== evidence.width || naturalSize.height !== evidence.height || location.image_width !== naturalSize.width || location.image_height !== naturalSize.height) return null
  if (!Array.isArray(box) || box.length !== 4 || box.some(x => !Number.isFinite(x))) return null
  const [x1,y1,x2,y2] = box
  if (x1 < 0 || y1 < 0 || x2 <= x1 || y2 <= y1 || x2 > naturalSize.width || y2 > naturalSize.height) return null
  return { x: x1, y: y1, width: x2-x1, height: y2-y1 }
}

export async function verifyEvidenceDigest(buffer, expected, cryptoApi = globalThis.crypto) {
  if (!(buffer instanceof ArrayBuffer) || !buffer.byteLength || buffer.byteLength > 8*1024*1024 || !/^[a-f0-9]{64}$/.test(expected || '')) throw new Error('关键帧数据无效，请重新打开。')
  if (!cryptoApi?.subtle) return 'unavailable'
  const digest = Array.from(new Uint8Array(await cryptoApi.subtle.digest('SHA-256', buffer)), value => value.toString(16).padStart(2,'0')).join('')
  if (digest !== expected) throw new Error('关键帧与详情记录不一致，请重新打开。')
  return 'valid'
}

export function sourceStateLabel(source) {
  if (source?.processing_state === 'paused' || source?.enabled === false) return '已暂停'
  if (source?.processing_state === 'leased') return '任务已领取'
  if (source?.processing_state === 'waiting_for_worker') return '等待处理'
  return '状态未知'
}
