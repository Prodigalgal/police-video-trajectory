import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React, { useEffect, useRef, useState } from 'react'
import RegionReview from './RegionReview'
import { Login } from './App.jsx'
import './evaluation.css'

const names = { nanodet_raw: 'NanoDet · 原图', nanodet_clahe: 'NanoDet · 低照增强', nanodet_tiled: 'NanoDet · 分块', yolox_raw: 'YOLOX · 原图', yolox_clahe: 'YOLOX · 低照增强', yolox_tiled: 'YOLOX · 分块' }
const classes = { person: '人', vehicle: '车辆（细类不明）', bicycle: '自行车', car: '汽车', motorcycle: '摩托车', bus: '公交/客车', truck: '货车' }
const conditions = { day: '白天', night: '夜间', headlights: '车灯反光', stationary: '静止目标', small_target: '小目标', occlusion: '遮挡' }
const statuses = { unreviewed: '待标注', draft: '草稿', verified: '人工已确认', ai_reviewed: "智能已复核" }
const percent = value => value == null ? '—' : `${(value * 100).toFixed(1)}%`
const seconds = value => `${Math.floor(value / 60000).toString().padStart(2, '0')}:${Math.floor(value / 1000 % 60).toString().padStart(2, '0')}`
async function api(path, options) {
  const response = await fetch(`/api/evaluation/${path}`, options)
  let data = {}; try { data = await response.json() } catch { /* generic error */ }
  if (!response.ok) throw new Error(chineseMessage(data.detail,'请求未完成，请核对标注或重新加载。'))
  return data
}

function Report({ threshold }) {
  const [report, setReport] = useState(null), [error, setError] = useState(''), [stratum, setStratum] = useState('all'), [measure, setMeasure] = useState('boxes'), [reference, setReference] = useState('human')
  useEffect(() => { let current = true; setReport(null); setError(''); api(`report?threshold=${threshold}&reference_source=${reference}`).then(v => current && setReport(v)).catch(e => current && setError(chineseMessage(e.message))); return () => { current = false } }, [threshold, reference])
  if (error) return <FloatingNotice role="alert" className="ev-error">{error}</FloatingNotice>
  if (!report) return <p role="status">正在核对标注与评测结果…</p>
  return <section className="ev-report">
    <div className="ev-report-intro"><div><p className="ev-eyebrow">{reference === 'human' ? '人工确认参考' : "智能复核参考 · 待独立抽核"}</p><h1 className="help-heading">准确率与资源，分别核对<InfoBubble title="使用与限制说明"><p className="ev-reference-note">{reference === 'ai_visual_review' ? "智能逐图复核仅作为初步参考，尚未经人工独立抽核；不会自动选定最优模型。" : "仅使用人工确认版本，智能复核不会计入人工真值。"} 未知/不可读帧排除 {report.excluded_unreadable_or_unknown} 张；人的正/负样本 {report.class_balance.person.positive}/{report.class_balance.person.negative}，车辆正/负样本 {report.class_balance.vehicle.positive}/{report.class_balance.vehicle.negative}。缺少负样本时不能验收误报率。完整框统计只用完整标注；部分框请选择“已标目标诊断”。</p></InfoBubble></h1><p>检出数量不代表准确率。草稿、未知和不可读样本按规则排除，缺少样本时显示“—”。</p></div><a className="ev-button" href="/api/evaluation/annotations/export">导出标注</a></div>
    <div className="ev-stat-grid"><article><span>{reference === 'human' ? '人工已确认' : "智能已复核"}</span><strong>{report.reference_reviewed_frames} / {report.total_frames}</strong></article><article><span>可纳入准确率</span><strong>{report.eligible_frames}</strong></article><article><span>人工确认 / 智能复核</span><strong>{report.verified_frames} / {report.ai_reviewed_frames}</strong></article><article><span>当前选型状态</span><strong className="ev-stat-text">{report.label_status === 'ready_for_preliminary_comparison' ? '可初步比较' : '等待标注与场景覆盖'}</strong></article></div>
    <div className="ev-report-controls"><label>参考来源<select value={reference} onChange={e => setReference(e.target.value)}><option value="human">人工确认</option><option value="ai_visual_review">智能复核（待独立抽核）</option></select></label><label>比较场景<select value={stratum} onChange={e => setStratum(e.target.value)}><option value="all">全部已确认样本</option>{Object.entries(conditions).map(([k, v]) => <option key={k} value={k}>{v} · {report.scenario_coverage[k]} 张</option>)}</select></label><label>比较指标<select value={measure} onChange={e => setMeasure(e.target.value)}><option value="boxes">目标框检出</option><option value="presence">人车存在性</option><option value="diagnostic">已标目标诊断（含部分框）</option></select></label><p>框匹配使用 框重叠率 ≥ 0.5；存在性判断与目标框检出分开统计；综合得分是查准率与查全率的调和平均。耗时来自同一处理环境，同图同条件比较。</p></div>
    
    {measure !== 'diagnostic' && <div className="ev-table-scroll"><table><caption>六种方案：优先核对漏检和误检，再核对算力预算</caption><thead>{measure === 'boxes' ? <tr><th>方案</th><th>完整框标注帧</th><th>框精确率</th><th>框召回率</th><th>人框召回</th><th>车框召回</th><th>框综合得分</th><th>漏检 / 误检</th><th>小目标召回</th><th>遮挡目标召回</th><th>中位耗时均值</th></tr> : <tr><th>方案</th><th>参考帧</th><th>人的精确率</th><th>人的召回率</th><th>车的精确率</th><th>车的召回率</th><th>存在性综合得分</th><th>存在性准确率</th><th>漏检 / 误检</th><th>中位耗时均值</th></tr>}</thead><tbody>{report.models.map(row => { const r = stratum === 'all' ? row : row.by_scenario[stratum]; return <tr key={row.model}><th>{names[row.model]}</th>{measure === 'boxes' ? <><td>{r.box_reviewed_frames}</td><td>{percent(r.boxes_iou50.precision)}</td><td>{percent(r.boxes_iou50.recall)}</td><td>{percent(r.type_box_metrics.person.recall)}</td><td>{percent(r.type_box_metrics.vehicle.recall)}</td><td>{percent(r.boxes_iou50.f1)}</td><td>{r.box_reviewed_frames ? `${r.boxes_iou50.fn} / ${r.boxes_iou50.fp}` : '—'}</td><td>{percent(r.small_targets.recall)}</td><td>{percent(r.occluded_targets.recall)}</td></> : <><td>{r.frames}</td><td>{percent(r.by_type.person.precision)}</td><td>{percent(r.by_type.person.recall)}</td><td>{percent(r.by_type.vehicle.precision)}</td><td>{percent(r.by_type.vehicle.recall)}</td><td>{percent(r.presence.f1)}</td><td>{percent(r.presence.accuracy)}</td><td>{r.presence.tp + r.presence.fp + r.presence.fn + r.presence.tn ? `${r.presence.fn} / ${r.presence.fp}` : '—'}</td></>}<td>{Math.round(row.latency.mean_camera_p50_ms)} 毫秒</td></tr> })}</tbody></table></div>}
    {measure === 'diagnostic' && <div className="ev-table-scroll"><table><caption>已标目标诊断：包含部分框；匹配率不是完整召回率，不计算未标区域的误检</caption><thead><tr><th>方案</th><th>有框参考帧</th><th>已标目标</th><th>匹配 / 未匹配</th><th>已标目标匹配率</th><th>已标人的匹配率</th><th>已标车的匹配率</th><th>中位耗时均值</th></tr></thead><tbody>{report.models.map(row => { const r = stratum === 'all' ? row : row.by_scenario[stratum], d = r.annotated_target_diagnostic; return <tr key={row.model}><th>{names[row.model]}</th><td>{d.frames_with_boxes}</td><td>{d.annotated_targets}</td><td>{d.annotated_targets ? `${d.matched_targets} / ${d.unmatched_targets}` : '—'}</td><td>{percent(d.match_rate)}</td><td>{percent(d.by_type.person.match_rate)}</td><td>{percent(d.by_type.vehicle.match_rate)}</td><td>{Math.round(row.latency.mean_camera_p50_ms)} 毫秒</td></tr> })}</tbody></table></div>}
    <div className="ev-report-notes"><article><h2>空景误剔除</h2><p>已确认有人车：{report.empty_gate.occupied_reviewed} 张，其中离线基线标为空景候选 {report.empty_gate.occupied_marked_empty} 张；比例 {percent(report.empty_gate.false_empty_rate)}。</p><p>已确认空景：{report.empty_gate.empty_reviewed} 张。没有空景负样本，不能判断过滤效果。</p></article><article><h2>事件是否漏采</h2><p>暂未评估。逐帧标注不能代替完整片段事件标注，也不能直接证明短暂人车事件被覆盖。</p><p>当前仅有四个点位的短录像，不自动宣布生产最优模型。</p></article></div>
  </section>
}

export default function Evaluation() {
  const [auth, setAuth] = useState(null), [dataset, setDataset] = useState(null), [frame, setFrame] = useState(null), [draft, setDraft] = useState(null)
  const [tab, setTab] = useState('label'), [camera, setCamera] = useState('all'), [actor, setActor] = useState(''), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false), [dirty, setDirty] = useState(false), [pendingFrame, setPendingFrame] = useState(null), [active, setActive] = useState(null)
  const [model, setModel] = useState('nanodet_raw'), [threshold, setThreshold] = useState(.35), [showPredictions, setShowPredictions] = useState(false), [predictions, setPredictions] = useState([])
  const [objectClass, setObjectClass] = useState('person'), [tool, setTool] = useState('draw'), [view, setView] = useState({ zoom: 1, x: 0, y: 0 }), [drag, setDrag] = useState(null), [showVideo, setShowVideo] = useState(false)
  const svg = useRef(null), video = useRef(null), loadingFrame = useRef(0)
  useEffect(() => { fetch('/api/session').then(r => r.json()).then(v => setAuth(v.authenticated)).catch(() => setAuth(false)) }, [])
  useEffect(() => { if (auth) api('dataset').then(setDataset).catch(e => setError(chineseMessage(e.message))) }, [auth])
  useEffect(() => { if (dataset && !frame) loadFrame(dataset.frames[0].id) }, [dataset])
  useEffect(() => {
    if (!frame || !showPredictions) { setPredictions([]); return }
    let current = true
    api(`frames/${frame.id}/predictions?model=${model}&threshold=${threshold}`).then(v => current && setPredictions(v.targets)).catch(e => current && setError(chineseMessage(e.message)))
    return () => { current = false }
  }, [frame?.id, model, threshold, showPredictions])
  async function loadFrame(id) {
    const request = ++loadingFrame.current; setError(''); setBusy(true)
    try { const value = await api(`frames/${id}`); if (request !== loadingFrame.current) return; setFrame(value); setDraft(value.label); setDirty(false); setActive(null); setView({ zoom: 1, x: 0, y: 0 }); setShowVideo(false); setNotice('') }
    catch (e) { if (request === loadingFrame.current) setError(chineseMessage(e.message)) } finally { if (request === loadingFrame.current) setBusy(false) }
  }
  function chooseFrame(id) { if (dirty) setPendingFrame(id); else loadFrame(id) }
  function change(patch) { setDraft(v => ({ ...v, ...patch })); setDirty(true); setNotice('') }
  function updateObject(id, patch) { change({ objects: draft.objects.map(v => v.id === id ? { ...v, ...patch } : v) }) }
  async function save(verify = false) {
    if (!actor.trim() || !draft) { setError('请先填写标注人员标识。'); return false }
    const invalid = draft.objects.findIndex(v => { const [x1, y1, x2, y2] = v.bbox_xyxy; return !v.bbox_xyxy.every(Number.isFinite) || !(0 <= x1 && x1 < x2 && x2 <= frame.width && 0 <= y1 && y1 < y2 && y2 <= frame.height) })
    if (invalid >= 0) { setError(`目标 ${invalid + 1} 的框坐标无效，请在坐标栏修正。`); return false }
    if (draft.scene === 'occupied' && draft.presence_person !== true && draft.presence_vehicle !== true) { setError('请在“是否有人 / 是否有车”中明确可见目标。'); return false }
    if (draft.objects.some(v => draft['presence_' + v.object_type] !== true)) { setError('存在性与目标框不一致：保留人车框时，相应的存在性应为“有”。'); return false }
    if (draft.boxes_complete && ['person', 'vehicle'].some(k => draft['presence_' + k] == null)) { setError('完整框标注需要先明确是否有人和是否有车；不能确认时请保留部分标注。'); return false }
    if (draft.boxes_complete && ['person', 'vehicle'].some(k => draft['presence_' + k] === true && !draft.objects.some(v => v.object_type === k))) { setError('完整框标注尚缺少可见目标；可先取消“已标出全部”保存部分标注。'); return false }
    setBusy(true); setError('')
    try {
      let saved = draft
      if (dirty || draft.status === 'unreviewed') {
        const body = Object.fromEntries(['revision', 'annotation_source', 'scene', 'presence_person', 'presence_vehicle', 'objects', 'boxes_complete', 'conditions', 'notes'].map(k => [k, draft[k]]))
        saved = await api(`frames/${frame.id}/label`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...body, actor: actor.trim() }) })
      }
      if (verify) saved = await api(`frames/${frame.id}/verify`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision: saved.revision, actor: actor.trim(), human_confirmed: true }) })
      setDraft(saved); setDirty(false); setDataset(v => ({ ...v, frames: v.frames.map(f => f.id === frame.id ? { ...f, status: saved.status } : f) })); setNotice(verify ? '已确认；该版本将按可评估范围参与准确率统计。' : '草稿已保存，尚不计入准确率。'); return true
    } catch (e) { setError(chineseMessage(e.message)); return false } finally { setBusy(false) }
  }
  function location(event) { return new DOMPoint(event.clientX, event.clientY).matrixTransform(svg.current.getScreenCTM().inverse()) }
  function pointerDown(event) {
    if (!frame || busy || event.button !== 0) return
    const p = location(event); svg.current.setPointerCapture(event.pointerId)
    setDrag({ start: p, end: p, clientX: event.clientX, clientY: event.clientY, x: view.x, y: view.y })
  }
  function pointerMove(event) {
    if (!drag) return
    if (tool === 'pan') {
      const bounds = svg.current.getBoundingClientRect(), w = frame.width / view.zoom, h = frame.height / view.zoom
      setView(v => ({ ...v, x: Math.max(0, Math.min(frame.width - w, drag.x - (event.clientX - drag.clientX) * w / bounds.width)), y: Math.max(0, Math.min(frame.height - h, drag.y - (event.clientY - drag.clientY) * h / bounds.height)) }))
    } else setDrag(v => ({ ...v, end: location(event) }))
  }
  function pointerUp(event) {
    if (!drag) return
    if (tool === 'draw') {
      const p = location(event), clamp = (v, max) => Math.max(0, Math.min(max, v))
      const box = [clamp(Math.min(drag.start.x, p.x), frame.width), clamp(Math.min(drag.start.y, p.y), frame.height), clamp(Math.max(drag.start.x, p.x), frame.width), clamp(Math.max(drag.start.y, p.y), frame.height)].map(v => Math.round(v * 10) / 10)
      if (box[2] - box[0] >= 3 && box[3] - box[1] >= 3) {
        const type = objectClass === 'person' ? 'person' : 'vehicle', id = crypto.randomUUID()
        change({ scene: 'occupied', ['presence_' + type]: true, objects: [...draft.objects, { id, object_type: type, class_name: objectClass, bbox_xyxy: box, occluded: false }] }); setActive(id)
      }
    }
    setDrag(null)
  }
  function zoom(delta) { setView(v => { const z = Math.max(1, Math.min(4, v.zoom + delta)); return { zoom: z, x: Math.min(v.x, frame.width - frame.width / z), y: Math.min(v.y, frame.height - frame.height / z) } }) }
  function addByCoordinates() {
    const id = crypto.randomUUID(), type = objectClass === 'person' ? 'person' : 'vehicle'
    change({ scene: 'occupied', ['presence_' + type]: true, objects: [...draft.objects, { id, object_type: type, class_name: objectClass, bbox_xyxy: [0, 0, Math.min(100, frame.width), Math.min(100, frame.height)], occluded: false }] }); setActive(id)
  }
  if (auth === null) return <main className="ev-loading" role="status">正在核对登录状态…</main>
  if (!auth) return <Login onLogin={() => setAuth(true)} context="evaluation"/>
  const currentObject = draft?.objects.find(v => v.id === active)
  const list = dataset?.frames.filter(v => camera === 'all' || v.camera === camera) || []
  return <main className="ev-root">
    <header className="ev-header"><a href="/" className="ev-brand">视迹<span>算法评测</span></a><nav aria-label="评测功能"><button aria-pressed={tab === 'label'} onClick={() => setTab('label')}>样本标注</button><button aria-pressed={tab === 'report'} onClick={() => setTab('report')}>准确率对照</button><button aria-pressed={tab === 'regions'} onClick={() => setTab('regions')}>空景对照</button></nav><span className="ev-paused">离线评测</span></header>
    <div className="ev-banner">只评测 1–4 号点位 · 原图保留 · 智能复核与人工确认分开记录 · 不创建业务分析任务</div>
    {error && <FloatingNotice className="ev-error" role="alert">{error}{error.includes('版本') && frame && <button onClick={() => loadFrame(frame.id)}>放弃本地修改并加载最新版本</button>}</FloatingNotice>}
    {pendingFrame && <FloatingNotice className="ev-unsaved" role="status" dismissible={false}>当前标注尚未保存。<button disabled={busy || !actor.trim()} onClick={async () => { const id = pendingFrame; if (await save()) { setPendingFrame(null); loadFrame(id) } }}>保存后切换</button><button onClick={() => setPendingFrame(null)}>继续当前标注</button></FloatingNotice>}
    {tab === 'regions' ? <RegionReview/> : tab === 'report' ? <><div className="ev-threshold"><label>检测阈值 <input type="range" min="0.05" max="0.95" step="0.05" value={threshold} onChange={e => setThreshold(Number(e.target.value))}/><output>{threshold.toFixed(2)}</output></label></div><Report threshold={threshold}/></> : <div className="ev-workspace">
      <aside className="ev-samples"><label>监控点位<select value={camera} onChange={e => setCamera(e.target.value)}><option value="all">全部四个点位</option>{dataset?.cameras.map(v => <option key={v} value={v}>{v}</option>)}</select></label><p>{list.length} 张原始样本 · 时间为片内偏移</p><div className="ev-frame-list">{list.map(item => <button className={frame?.id === item.id ? 'ev-frame active' : 'ev-frame'} key={item.id} onClick={() => chooseFrame(item.id)} aria-current={frame?.id === item.id ? 'true' : undefined}><img src={item.image_url} alt={`${item.camera} 片内 ${seconds(item.offset_ms)}`} loading="lazy"/><span><strong>{item.camera}</strong><small>{seconds(item.offset_ms)} · {statuses[item.status]}</small></span></button>)}</div></aside>
      <section className="ev-canvas-area" aria-label="原始画面标注">
        {!frame ? <p role="status">正在加载真实样本…</p> : <>
          <div className="ev-frame-heading"><div><h1>{frame.camera}<small>片内 {seconds(frame.offset_ms)}</small></h1><p>{frame.width} × {frame.height} · 原图坐标 · {dirty ? '有未保存改动' : statuses[draft.status]}</p></div><button className="ev-button" onClick={() => setShowVideo(v => !v)}>{showVideo ? '收起原片' : '查看原片'}</button></div>
          <div className="ev-tools"><div role="group" aria-label="标注工具"><button aria-pressed={tool === 'draw'} onClick={() => setTool('draw')}>画框</button><button aria-pressed={tool === 'pan'} onClick={() => setTool('pan')}>平移</button></div><label>目标<select value={objectClass} onChange={e => setObjectClass(e.target.value)}>{Object.entries(classes).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label><button onClick={addByCoordinates}>用坐标添加</button><div role="group" aria-label="画面缩放"><button aria-label="缩小画面" disabled={view.zoom <= 1} onClick={() => zoom(-.5)}>−</button><span>{view.zoom.toFixed(1)}×</span><button aria-label="放大画面" disabled={view.zoom >= 4} onClick={() => zoom(.5)}>＋</button><button onClick={() => setView({ zoom: 1, x: 0, y: 0 })}>复位</button></div></div>
          <svg ref={svg} className={`ev-canvas ${tool}`} viewBox={`${view.x} ${view.y} ${frame.width / view.zoom} ${frame.height / view.zoom}`} role="img" aria-label={`${frame.camera} 原图；可画框，也可用右侧坐标输入标注`} onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={() => setDrag(null)}><image href={frame.image_url} width={frame.width} height={frame.height}/>{predictions.map((v, i) => <rect key={`prediction-${i}`} className="ev-prediction" x={v.bbox_xyxy[0]} y={v.bbox_xyxy[1]} width={v.bbox_xyxy[2] - v.bbox_xyxy[0]} height={v.bbox_xyxy[3] - v.bbox_xyxy[1]}/>)}{draft.objects.map((v, i) => <g key={v.id} className={v.id === active ? 'ev-box selected' : 'ev-box'} onPointerDown={e => { e.stopPropagation(); setActive(v.id) }}><rect x={v.bbox_xyxy[0]} y={v.bbox_xyxy[1]} width={v.bbox_xyxy[2] - v.bbox_xyxy[0]} height={v.bbox_xyxy[3] - v.bbox_xyxy[1]}/><text x={v.bbox_xyxy[0] + 4} y={Math.max(20, v.bbox_xyxy[1] - 6)}>{i + 1} · {classes[v.class_name]}</text></g>)}{drag && tool === 'draw' && <rect className="ev-drawing" x={Math.min(drag.start.x, drag.end.x)} y={Math.min(drag.start.y, drag.end.y)} width={Math.abs(drag.start.x - drag.end.x)} height={Math.abs(drag.start.y - drag.end.y)}/>}</svg>
          <p className="ev-legend">实线：{draft.annotation_source === 'ai_visual_review' ? "智能复核框（待独立抽核）" : '人工标注'}　虚线：检测器建议。小目标可放大后标注；遮挡或看不清时保留不确定。</p>
          {showVideo && <div className="ev-video"><video ref={video} controls preload="metadata" src={`/api/evaluation/recordings/${frame.camera}`} onLoadedMetadata={() => { if (video.current) video.current.currentTime = frame.offset_ms / 1000 }}/><p>原片时间为相对偏移，尚不是经核验的摄像头拍摄时间。</p></div>}
          <details className="ev-comparison"><summary>查看模型建议（辅助对照，不自动写入标注）</summary><div><label><input type="checkbox" checked={showPredictions} onChange={e => setShowPredictions(e.target.checked)}/>显示建议框</label><label>候选方案<select value={model} onChange={e => setModel(e.target.value)}>{dataset?.models.map(v => <option key={v} value={v}>{names[v]}</option>)}</select></label><label>检测阈值<input type="range" min="0.05" max="0.95" step="0.05" value={threshold} onChange={e => setThreshold(Number(e.target.value))}/><output>{threshold.toFixed(2)}</output></label></div></details>
        </>}
      </section>
      <aside className="ev-inspector"><h2>确认可见事实</h2><label>标注人员标识（自填）<input value={actor} onChange={e => setActor(e.target.value)} maxLength={80} placeholder="用于记录本次标注"/></label>
        {draft && <>
          <p className="ev-help">标注来源：{draft.annotation_source === 'ai_visual_review' ? "智能逐图复核" : '人工录入'}；确认来源：{draft.review_source === 'ai_visual_review' ? "智能复核，尚无人工独立确认" : draft.review_source === 'human' ? '人工确认' : '尚未确认'}。</p><label>画面状态<select value={draft.scene} onChange={e => change(e.target.value === 'empty' ? { scene: 'empty', presence_person: false, presence_vehicle: false, objects: [], boxes_complete: true } : { scene: e.target.value, boxes_complete: false })}><option value="unknown">待判断 / 不确定</option><option value="occupied">可见人或车</option><option value="empty">明确无人无车</option><option value="unreadable">画面不可读</option></select></label>
          <div className="ev-presence">{['person', 'vehicle'].map(kind => <label key={kind}>{kind === 'person' ? '是否有人' : '是否有车'}<select value={draft['presence_' + kind] == null ? 'unknown' : String(draft['presence_' + kind])} onChange={e => change({ ['presence_' + kind]: e.target.value === 'unknown' ? null : e.target.value === 'true' })}><option value="unknown">不能确认</option><option value="true">有</option><option value="false">没有</option></select></label>)}</div>
          <fieldset className="ev-conditions"><legend>场景条件</legend>{Object.entries(conditions).map(([key, label]) => <label key={key}><input type="checkbox" checked={draft.conditions.includes(key)} onChange={e => change({ conditions: e.target.checked ? [...draft.conditions.filter(v => !(key === 'day' && v === 'night' || key === 'night' && v === 'day')), key] : draft.conditions.filter(v => v !== key) })}/>{label}</label>)}</fieldset>
          <div className="ev-objects"><h3>目标框 · {draft.objects.length}</h3>{draft.objects.map((v, i) => <div key={v.id} className={active === v.id ? 'ev-object active' : 'ev-object'}><button onClick={() => setActive(v.id)}>{i + 1} · {classes[v.class_name]}</button><button aria-label={`移除目标 ${i + 1}`} onClick={() => { change({ objects: draft.objects.filter(t => t.id !== v.id) }); setActive(null) }}>移除</button></div>)}{!draft.objects.length && <p>在原图拖动画框，或用坐标添加。未标框的存在性判断仍可单独评估。</p>}</div>
          {currentObject && <fieldset className="ev-coordinates"><legend>目标 {draft.objects.indexOf(currentObject) + 1} · 原图像素坐标</legend>{['左 X', '上 Y', '右 X', '下 Y'].map((label, i) => <label key={label}>{label}<input type="number" step="0.1" min="0" max={i % 2 === 0 ? frame.width : frame.height} value={currentObject.bbox_xyxy[i]} onChange={e => { const box = [...currentObject.bbox_xyxy]; box[i] = Number(e.target.value); updateObject(currentObject.id, { bbox_xyxy: box }) }}/></label>)}<label className="ev-span"><input type="checkbox" checked={currentObject.occluded} onChange={e => updateObject(currentObject.id, { occluded: e.target.checked })}/>目标有遮挡</label></fieldset>}
          <label className="ev-completeness"><input type="checkbox" checked={draft.boxes_complete} onChange={e => change({ boxes_complete: e.target.checked })}/>已标出全部可辨认人车框</label><p className="ev-help">不完整的框标注不计入框精确率、召回率；看不清的目标不强行猜测。</p>
          <label>备注<textarea value={draft.notes} maxLength={500} onChange={e => change({ notes: e.target.value })} rows={2}/></label>
          <div className="ev-actions"><button disabled={busy || !actor.trim()} onClick={() => save(false)}>保存草稿</button><button className="ev-primary" disabled={busy || !actor.trim() || draft.status === 'verified' && !dirty} onClick={() => save(true)}>{busy ? '正在保存…' : '已逐框核对，确认标注'}</button><InfoBubble title="标注确认说明">确认前请核对存在性、目标框、遮挡及场景条件。确认后才会按范围计入评测。</InfoBubble></div>
          {notice&&<FloatingNotice>{notice}</FloatingNotice>}
        </>}
      </aside>
    </div>}
  </main>
}
