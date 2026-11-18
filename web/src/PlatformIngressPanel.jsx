import React, { useEffect, useRef, useState } from 'react'
import { FloatingNotice, InfoBubble } from './Feedback.jsx'
import { chineseMessage } from './chinese-copy.js'

const emptyPlatform = {
  platform_id: 'wvp-main',
  adapter_kind: 'gb28181_cascade',
  display_name: '上级国标级联平台',
  endpoint_ref: 'env:WVP_PRO_ENDPOINT',
  media_node: 'zlm-edge-01',
  clock_state: 'unverified',
  clock_offset_ms: '',
  enabled: false,
}

const emptyChannel = {
  device_id: '',
  channel_id: '',
  camera_id: '',
  channel_name: '',
  stream_profile: 'main',
  clock_verified: false,
  clock_offset_ms: '',
  active: true,
}

const adapterLabels = {
  gb28181_cascade: '国标级联接入（流媒体服务托管）',
  platform_stream: '平台受控转出流接入',
}

const sessionStateLabels = {
  registered: '已注册',
  active: '拉流中',
  closed: '已关闭',
  failed: '异常',
}

export default function PlatformIngressPanel({ cameras, refresh, onError }) {
  const [data, setData] = useState(null)
  const [selectedId, setSelectedId] = useState('')
  const [channels, setChannels] = useState([])
  const [sessions, setSessions] = useState([])
  const [platformDraft, setPlatformDraft] = useState(emptyPlatform)
  const [channelDraft, setChannelDraft] = useState(emptyChannel)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState(null)
  const [reload, setReload] = useState(0)
  const callbacks = useRef(onError)
  callbacks.current = onError

  async function api(path, options = {}) {
    const response = await fetch('/api/admin/' + path, {
      ...options,
      cache: 'no-store',
      headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    })
    let body = {}
    try { body = await response.json() } catch { /* generic fallback */ }
    if (!response.ok) {
      const messages = {
        invalid_platform_endpoint: '平台端点必须填逻辑凭据引用，禁止填写含密码或协议头的明文地址。',
        platform_clock_unverified: '平台或通道时钟未核验，不能设置时钟偏差。',
        platform_revision_conflict: '平台或通道配置已被其他会话更新，请刷新后再保存。',
        platform_mapping_conflict: '该内部监控点位已绑定到其他有效国标通道，请先停用原绑定。',
        platform_registry_full: '平台或通道数量已达系统上限。',
        platform_unavailable: '所选视频平台不存在或不可用。',
      }
      const err = new Error(messages[body.code] || chineseMessage(body.detail, '视频平台配置服务暂不可用，请稍后重试。'))
      err.unauthorized = response.status === 401
      err.code = body.code
      throw err
    }
    return body
  }

  useEffect(() => {
    const abort = new AbortController()
    setLoading(true)
    Promise.all([
      api('platforms', { signal: abort.signal }),
      api('platforms/sessions', { signal: abort.signal }).catch(() => ({ items: [] })),
    ]).then(([platRes, sessRes]) => {
      if (abort.signal.aborted) return
      setData(platRes)
      setSessions(Array.isArray(sessRes?.items) ? sessRes.items : [])
      const items = platRes.items || []
      const chosen = items.find(item => item.platform_id === selectedId) || items[0] || null
      if (chosen) {
        setSelectedId(chosen.platform_id)
        setPlatformDraft({
          platform_id: chosen.platform_id,
          adapter_kind: chosen.adapter_kind,
          display_name: chosen.display_name,
          endpoint_ref: chosen.endpoint_ref,
          media_node: chosen.media_node || '',
          clock_state: chosen.clock_state || 'unverified',
          clock_offset_ms: chosen.clock_offset_ms ?? '',
          enabled: Boolean(chosen.enabled),
        })
      }
    }).catch(err => {
      if (!abort.signal.aborted) {
        setNotice({ error: true, text: chineseMessage(err.message) })
        if (err.unauthorized) callbacks.current?.(err)
      }
    }).finally(() => {
      if (!abort.signal.aborted) setLoading(false)
    })
    return () => abort.abort()
  }, [refresh, reload])

  useEffect(() => {
    if (!selectedId || !data?.items?.some(item => item.platform_id === selectedId)) {
      setChannels([])
      return
    }
    const abort = new AbortController()
    api(`platforms/${encodeURIComponent(selectedId)}/channels`, { signal: abort.signal })
      .then(res => { if (!abort.signal.aborted) setChannels(res.items || []) })
      .catch(err => { if (!abort.signal.aborted && err.unauthorized) callbacks.current?.(err) })
    return () => abort.abort()
  }, [selectedId, data, reload])

  const currentPlatform = data?.items?.find(item => item.platform_id === selectedId)

  async function savePlatform(event) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setNotice(null)
    try {
      const existing = data?.items?.find(item => item.platform_id === platformDraft.platform_id.trim())
      const offset = platformDraft.clock_state === 'verified' && platformDraft.clock_offset_ms !== ''
        ? Number(platformDraft.clock_offset_ms) : null
      const res = await api(`platforms/${encodeURIComponent(platformDraft.platform_id.trim())}`, {
        method: 'PUT',
        body: JSON.stringify({
          request_id: crypto.randomUUID(),
          expected_revision: existing ? existing.revision : 0,
          adapter_kind: platformDraft.adapter_kind,
          display_name: platformDraft.display_name.trim(),
          endpoint_ref: platformDraft.endpoint_ref.trim(),
          media_node: platformDraft.media_node.trim(),
          clock_state: platformDraft.clock_state,
          clock_offset_ms: offset,
          enabled: Boolean(platformDraft.enabled),
        }),
      })
      setSelectedId(res.platform.platform_id)
      setNotice({ error: false, text: '平台接入边界已保存，实际国标与媒体互操作需现场平台联调后核验。' })
      setReload(v => v + 1)
    } catch (err) {
      setNotice({ error: true, text: chineseMessage(err.message) })
      if (err.unauthorized) callbacks.current?.(err)
    } finally {
      setBusy(false)
    }
  }

  async function saveChannel(event) {
    event.preventDefault()
    if (busy || !selectedId) return
    setBusy(true)
    setNotice(null)
    try {
      const existing = channels.find(
        item => item.device_id === channelDraft.device_id.trim() && item.channel_id === channelDraft.channel_id.trim()
      )
      const offset = channelDraft.clock_verified && channelDraft.clock_offset_ms !== ''
        ? Number(channelDraft.clock_offset_ms) : null
      await api(`platforms/${encodeURIComponent(selectedId)}/channels`, {
        method: 'PUT',
        body: JSON.stringify({
          request_id: crypto.randomUUID(),
          expected_revision: existing ? existing.revision : 0,
          device_id: channelDraft.device_id.trim(),
          channel_id: channelDraft.channel_id.trim(),
          camera_id: channelDraft.camera_id,
          channel_name: channelDraft.channel_name.trim(),
          stream_profile: channelDraft.stream_profile,
          clock_verified: Boolean(channelDraft.clock_verified),
          clock_offset_ms: offset,
          active: Boolean(channelDraft.active),
        }),
      })
      setChannelDraft(emptyChannel)
      setNotice({ error: false, text: '国标设备通道与内部点位映射已保存。' })
      setReload(v => v + 1)
    } catch (err) {
      setNotice({ error: true, text: chineseMessage(err.message) })
      if (err.unauthorized) callbacks.current?.(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="admin-section-title">
        <div>
          <h1>视频平台与级联接入</h1>
          <p>登记上级国标级联平台与受控转出流适配器，维护设备通道与内部监控点位的复合映射。</p>
        </div>
        <InfoBubble title="上级平台与录像边界说明">
          本系统不保存明文接入密码或向浏览器暴露带鉴权取流地址；未经过实际平台联调前，现场互操作与绝对拍摄时间均明确标为待核验，不使用接收时间代替源时间，也不恢复本地有效帧录像留存。
        </InfoBubble>
      </div>
      {notice && <FloatingNotice className={notice.error ? 'error' : 'admin-success'} role={notice.error ? 'alert' : 'status'}>{notice.text}</FloatingNotice>}
      {loading && !data ? <p role="status">正在读取视频平台配置…</p> : (
        <div className="overview-grid">
          <section className="admin-card">
            <div className="admin-card-heading">
              <h2>已登记视频平台</h2>
              <span className="admin-muted">{(data?.items || []).length} 个平台</span>
            </div>
            {(data?.items || []).length ? (
              <div className="admin-table-wrap">
                <table className="admin-table admin-table-fit">
                  <thead>
                    <tr>
                      <th>平台与接入类型</th>
                      <th>媒体节点与凭据引用</th>
                      <th>核验状态</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map(item => (
                      <tr key={item.platform_id} className={selectedId === item.platform_id ? 'is-selected' : ''}>
                        <td>
                          <strong>{item.display_name}</strong>
                          <p className="admin-muted">{item.platform_id} · {adapterLabels[item.adapter_kind] || item.adapter_kind}</p>
                        </td>
                        <td>
                          <code>{item.endpoint_ref}</code>
                          {item.media_node && <p className="admin-muted">媒体节点：{item.media_node}</p>}
                        </td>
                        <td>
                          <span className="admin-badge">{item.enabled ? '已启用配置' : '已停用'}</span>
                          <p className="admin-muted">
                            {item.interoperability_verified ? '已互操作验收' : '未现场联调'} · {item.clock_state === 'verified' ? '时钟已核验' : '时钟待核验'}
                          </p>
                        </td>
                        <td>
                          <button type="button" className="text-button" onClick={() => {
                            setSelectedId(item.platform_id)
                            setPlatformDraft({
                              platform_id: item.platform_id,
                              adapter_kind: item.adapter_kind,
                              display_name: item.display_name,
                              endpoint_ref: item.endpoint_ref,
                              media_node: item.media_node || '',
                              clock_state: item.clock_state || 'unverified',
                              clock_offset_ms: item.clock_offset_ms ?? '',
                              enabled: Boolean(item.enabled),
                            })
                          }}>选择</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : <p className="admin-empty">暂未登记外部视频平台，可在右侧表单新增级联或转出流接入配置。</p>}
          </section>

          <form className="admin-card admin-camera-form" onSubmit={savePlatform}>
            <div className="admin-card-heading">
              <h2>{currentPlatform ? `编辑平台：${currentPlatform.platform_id}` : '登记视频平台'}</h2>
              <button type="button" className="text-button" disabled={busy} onClick={() => setPlatformDraft(emptyPlatform)}>新建空白项</button>
            </div>
            <label>平台编号（小写字母、数字或短横线）
              <input value={platformDraft.platform_id} pattern="[a-z0-9][a-z0-9_\-]{0,79}" required disabled={busy}
                onChange={e => setPlatformDraft({ ...platformDraft, platform_id: e.target.value })} />
            </label>
            <label>接入插件类型
              <select value={platformDraft.adapter_kind} disabled={busy}
                onChange={e => setPlatformDraft({ ...platformDraft, adapter_kind: e.target.value })}>
                <option value="gb28181_cascade">国标级联接入（国标信令与流媒体协同）</option>
                <option value="platform_stream">平台受控转出流接入</option>
              </select>
            </label>
            <label>显示名称
              <input value={platformDraft.display_name} maxLength={200} required disabled={busy}
                onChange={e => setPlatformDraft({ ...platformDraft, display_name: e.target.value })} />
            </label>
            <label>受控端点或凭据引用名
              <input value={platformDraft.endpoint_ref} maxLength={120} required disabled={busy}
                onChange={e => setPlatformDraft({ ...platformDraft, endpoint_ref: e.target.value })} />
            </label>
            <label>流媒体服务节点标识
              <input value={platformDraft.media_node} maxLength={200} disabled={busy}
                onChange={e => setPlatformDraft({ ...platformDraft, media_node: e.target.value })} />
            </label>
            <label>平台时钟核验状态
              <select value={platformDraft.clock_state} disabled={busy}
                onChange={e => setPlatformDraft({ ...platformDraft, clock_state: e.target.value })}>
                <option value="unverified">时钟待核验（不推导绝对拍摄时间）</option>
                <option value="verified">已完成校时核验</option>
              </select>
            </label>
            {platformDraft.clock_state === 'verified' && (
              <label>已核验时钟偏差（毫秒）
                <input type="number" value={platformDraft.clock_offset_ms} disabled={busy}
                  onChange={e => setPlatformDraft({ ...platformDraft, clock_offset_ms: e.target.value })} />
              </label>
            )}
            <label className="admin-check">
              <input type="checkbox" checked={platformDraft.enabled} disabled={busy}
                onChange={e => setPlatformDraft({ ...platformDraft, enabled: e.target.checked })} />
              启用该平台配置（启用仅保存元数据，不自动启动实时任务）
            </label>
            <div className="admin-form-actions">
              <button type="submit" className="admin-button" disabled={busy}>{busy ? '正在保存…' : '保存平台配置'}</button>
            </div>
          </form>
        </div>
      )}

      {selectedId && (
        <section className="admin-card">
          <div className="admin-card-heading">
            <h2>国标通道与内部点位映射（{selectedId}）</h2>
            <span className="admin-muted">使用平台、设备与通道复合标识定位点位</span>
          </div>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>设备编号</th>
                  <th>通道编号</th>
                  <th>关联监控点位</th>
                  <th>码流与时钟</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                {channels.map(ch => (
                  <tr key={ch.mapping_id}>
                    <td><code>{ch.device_id}</code></td>
                    <td><code>{ch.channel_id}</code></td>
                    <td><strong>{ch.camera_display_name || ch.camera_id}</strong> <span className="admin-muted">({ch.camera_id})</span></td>
                    <td>{ch.stream_profile === 'main' ? '主码流' : ch.stream_profile === 'sub' ? '子码流' : '未标定'} · {ch.clock_verified ? '时钟已核验' : '时钟待核验'}</td>
                    <td><span className="admin-badge">{ch.active ? '生效中' : '已停用'}</span></td>
                  </tr>
                ))}
                {!channels.length && <tr><td colSpan={5} className="admin-empty">当前平台下尚未登记国标通道映射。</td></tr>}
              </tbody>
            </table>
          </div>

          <form className="admin-camera-form" onSubmit={saveChannel}>
            <h3>新增或更新通道映射</h3>
            <label>国标设备编号
              <input value={channelDraft.device_id} maxLength={64} placeholder="例如：34020000001320000001" required disabled={busy}
                onChange={e => setChannelDraft({ ...channelDraft, device_id: e.target.value })} />
            </label>
            <label>国标通道编号
              <input value={channelDraft.channel_id} maxLength={64} placeholder="例如：34020000001310000001" required disabled={busy}
                onChange={e => setChannelDraft({ ...channelDraft, channel_id: e.target.value })} />
            </label>
            <label>内部监控点位
              <select value={channelDraft.camera_id} required disabled={busy}
                onChange={e => setChannelDraft({ ...channelDraft, camera_id: e.target.value })}>
                <option value="">选择监控点位</option>
                {(cameras || []).map(c => <option key={c.camera_id} value={c.camera_id}>{c.display_name} ({c.camera_id})</option>)}
              </select>
            </label>
            <label>码流配置
              <select value={channelDraft.stream_profile} disabled={busy}
                onChange={e => setChannelDraft({ ...channelDraft, stream_profile: e.target.value })}>
                <option value="main">主码流</option>
                <option value="sub">子码流</option>
                <option value="unknown">未标定</option>
              </select>
            </label>
            <label className="admin-check">
              <input type="checkbox" checked={channelDraft.active} disabled={busy}
                onChange={e => setChannelDraft({ ...channelDraft, active: e.target.checked })} />
              激活该映射（单点位同时只允许一个有效国标通道映射）
            </label>
            <button type="submit" className="admin-button" disabled={busy || !channelDraft.camera_id}>{busy ? '正在保存…' : '保存通道映射'}</button>
          </form>
        </section>
      )}

      {sessions.length > 0 && (
        <section className="admin-card">
          <div className="admin-card-heading">
            <h2>平台媒体会话代次（近期）</h2>
            <span className="admin-muted">断线重连递增代次，不伪造连续片段</span>
          </div>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr><th>点位与平台</th><th>会话类型</th><th>重连代次</th><th>状态</th><th>逻辑句柄</th></tr>
              </thead>
              <tbody>
                {sessions.map(s => (
                  <tr key={s.session_id}>
                    <td>{s.camera_id} · {s.platform_id}</td>
                    <td>{s.session_kind === 'live' ? '实时' : '回放'} ({s.stream_profile === 'main' ? '主码流' : s.stream_profile === 'sub' ? '子码流' : '未标定'})</td>
                    <td>{s.media_epoch}</td>
                    <td><span className="admin-badge">{sessionStateLabels[s.state] || '已登记'}</span></td>
                    <td><code>{s.media_handle_ref}</code></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </>
  )
}
