import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React, { useEffect, useState } from 'react'
import PolicyComparison from './PolicyComparison'

const states = { empty: '区域空景', occupied: '可见人车', unknown: '不能确认', unreviewed: '尚未复核', empty_candidate: '空景候选', occupied_quiet: '静止目标', active: '活动目标' }
const methods = { mad: '帧差', robust: '稳健帧差', mog2: '高斯混合背景法', knn: '近邻背景法' }
const pct = n => n == null ? '—' : `${(n * 100).toFixed(1)}%`
async function get(path, signal) {
  const r = await fetch(`/api/evaluation/regions/${path}`, { signal })
  const data = await r.json()
  if (!r.ok) throw new Error(chineseMessage(data.detail,'空景对照暂时不可用'))
  return data
}

export default function RegionReview() {
  const [data, setData] = useState(null), [error, setError] = useState('')
  const [camera, setCamera] = useState('recording-01'), [region, setRegion] = useState('near_field'), [index, setIndex] = useState(0), [interval, setInterval] = useState(5)
  const [detail, setDetail] = useState(null), [report, setReport] = useState(null)
  const [loadedImage, setLoadedImage] = useState(''), [failedImage, setFailedImage] = useState('')
  useEffect(() => {
    const c = new AbortController()
    get('dataset', c.signal).then(setData).catch(e => { if (e.name !== 'AbortError') setError(chineseMessage(e.message)) })
    return () => c.abort()
  }, [])
  const frames = data?.frames.filter(v => v.camera === camera) || [], frame = frames[index]
  useEffect(() => {
    if (!frame) return
    const c = new AbortController(); setDetail(null); setError('')
    get(`frames/${frame.id}?region=${region}`, c.signal).then(setDetail).catch(e => { if (e.name !== 'AbortError') setError(chineseMessage(e.message)) })
    return () => c.abort()
  }, [frame?.id, region])
  useEffect(() => {
    const c = new AbortController(); setReport(null)
    get(`report?camera=${camera}&region=${region}&interval=${interval}`, c.signal).then(setReport).catch(e => { if (e.name !== 'AbortError') setError(chineseMessage(e.message)) })
    return () => c.abort()
  }, [camera, region, interval])
  if (!data) return <section className="ev-report">{error?<FloatingNotice kind="error">{error}</FloatingNotice>:<p role="status">正在加载四个点位的真实片段…</p>}</section>
  const box = data.effective_rectangles[camera][region]
  return <section className="ev-report ev-region">
    <div className="ev-report-intro"><div><p className="ev-eyebrow">连续时段快照 · 智能复核参考</p><h1 className="help-heading">先核对误剔除，再比较过滤量<InfoBubble title="使用与限制说明"><p className="ev-reference-note">{region === 'near_field' ? '蓝框内判断局部空景；框外的停放车辆和其他目标仍属于全画面。局部为空不能推出全景为空。' : '全画面包含停放车辆、远处和边缘目标；尚未逐图复核的范围不计算准确率。'} 当前仅做离线对照，生产区域设置保持原状。</p></InfoBubble></h1><p>1–4 号真实录像，每路 20 秒、每 0.5 秒一张。结果尚未经人工独立抽核。</p></div></div>
    <div className="ev-report-controls"><label>监控点位<select value={camera} onChange={e => { setCamera(e.target.value); setIndex(0) }}>{data.cameras.map(v => <option key={v}>{v}</option>)}</select></label><label>评测观察范围<select value={region} onChange={e => setRegion(e.target.value)}><option value="full_frame">全画面</option><option value="near_field">局部路面观察窗</option></select></label><label>候选输出间隔<select value={interval} onChange={e => setInterval(Number(e.target.value))}><option value={1}>1 秒</option><option value={5}>5 秒</option></select></label></div>
    
    {error && <FloatingNotice role="alert" className="ev-error">{error}</FloatingNotice>}
    {frame && <div className="ev-region-review"><div>
      <div className="ev-region-visual"><svg key={frame.id} className="ev-region-image" viewBox="0 0 1280 960" role="img" aria-label={`${camera} 片内 ${(frame.offset_ms / 1000).toFixed(1)} 秒，蓝框为当前评测区域`}><image href={frame.image_url} width="1280" height="960" onLoad={() => setLoadedImage(frame.id)} onError={() => setFailedImage(frame.id)}/><rect x={box[0] + 1} y={box[1] + 1} width={box[2] - box[0] - 2} height={box[3] - box[1] - 2} fill="none" stroke="#64caff" strokeWidth="5"/></svg>{loadedImage !== frame.id && <p className="ev-region-image-status" role={failedImage === frame.id ? 'alert' : 'status'}>{failedImage === frame.id ? '原图加载失败，请重新加载页面。' : '正在加载真实画面…'}</p>}</div>
      <div className="ev-region-timeline"><button disabled={index === 0} onClick={() => setIndex(v => v - 1)}>上一张</button><label>片内 {(frame.offset_ms / 1000).toFixed(1)} 秒<input aria-label="连续快照位置" type="range" min="0" max={frames.length - 1} value={index} onChange={e => setIndex(Number(e.target.value))}/></label><button disabled={index === frames.length - 1} onClick={() => setIndex(v => v + 1)}>下一张</button></div>
    </div><aside className="ev-region-detail"><h2>{states[frame.references[region]]}</h2><p>参考来源：智能逐图复核</p><p>{detail ? detail.reference?.notes || '该范围尚无已保存的逐图参考。' : '正在加载区域复核…'}</p><p>参考时刻 {(frame.offset_ms / 1000).toFixed(1)} 秒；对应先前探测时刻 {detail ? detail.trace_offset_ms == null ? '无' : `${(detail.trace_offset_ms / 1000).toFixed(1)} 秒` : '加载中'}。</p><p>探测每 1 秒、检测器每 5 秒更新；快照对应此前已得到的状态，不借用未来检测。</p>{detail?.sample && ['nanodet', 'yolox'].map(model => <div key={model}><h3>{model === 'nanodet' ? 'NanoDet' : 'YOLOX'}</h3>{Object.keys(methods).map(m => <p key={m}>{methods[m]}：{states[detail.sample.models[model].gate_candidates[`${m}_${interval}s`].state] || '未知'}</p>)}</div>)}</aside></div>}
    {report ? <><div className="ev-stat-grid"><article><span>有目标参考快照</span><strong>{report.rows[0].occupied_reference_samples}</strong></article><article><span>空景参考快照</span><strong>{report.rows[0].empty_reference_samples}</strong></article><article><span>未知 / 尚未复核</span><strong>{report.unknown_or_unreviewed_samples}</strong></article><article><span>连续事件召回率</span><strong className="ev-stat-text">尚未评估</strong></article></div><div className="ev-table-scroll"><table><caption>快照诊断：误剔除按“有目标却被门控标为空景候选”计算</caption><thead><tr><th>检测器 / 运动算法</th><th>有目标误标空景</th><th>误剔除比例</th><th>空景标为空景</th><th>空景候选比例</th><th>未知状态 / 无匹配</th><th>全片候选输出</th><th>检测 中位数 / 九五分位</th></tr></thead><tbody>{report.rows.map(r => <tr key={`${r.model}-${r.motion_method}`}><th>{r.model} / {methods[r.motion_method]}</th><td>{r.occupied_marked_empty} / {r.matched_occupied_samples}</td><td>{pct(r.false_empty_rate)}</td><td>{r.empty_marked_empty} / {r.matched_empty_samples}</td><td>{pct(r.empty_candidate_rate)}</td><td>{r.unknown_gate_samples} / {r.unmatched_samples}</td><td>{r.candidate_frames_in_whole_clip}</td><td>{Math.round(r.latency.p50_ms)} / {Math.round(r.latency.p95_ms)} 毫秒</td></tr>)}</tbody></table></div></> : <p role="status">正在计算同范围对照…</p>}
    <div className="ev-report-notes"><article><h2>快照与事件分开验收</h2><p>0.5 秒快照只证明这些时刻的可见状态，不能排除两张之间的短暂目标。下方策略对照另有局部逐源帧区间参考，仍需补齐独立场景和人工抽核。</p></article><article><h2>候选量与实际负载分开核对</h2><p>误剔除只统计所选 20 秒复核窗口。全片候选输出来自约 2 分钟录像的门控模拟，尚未包含选优、编码、队列或大模型耗时，不能直接当作调用节省量。</p></article></div>
    {region === 'near_field' && <PolicyComparison/>}
  </section>
}
