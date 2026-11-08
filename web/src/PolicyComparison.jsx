import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React, { useEffect, useState } from 'react'

const modes = { none: '原有全局运动', '320px_5hz': '320 像素 · 局部运动保护', '640px_5hz': '640 像素 · 局部运动保护', '320px_5hz_peak': '320 像素 · 保护并选峰值帧', '640px_5hz_peak': '640 像素 · 保护并选峰值帧' }
const sec = n => (n / 1000).toFixed(2)

function Scores({ row, label }) {
  return <tr><th>{label || `${row.policy.detector_seconds} 秒 / ${row.policy.negative_confirmations} 次 / ${row.policy.active_hold_seconds} 秒`}</th><td>{row.snapshots.occupied_marked_empty} / {row.snapshots.occupied_samples}</td><td>{row.snapshots.empty_marked_empty} / {row.snapshots.empty_samples}</td><td>{sec(row.temporal.occupied_marked_empty_ms)} 秒</td><td>{sec(row.temporal.empty_marked_empty_ms)} 秒</td><td>{row.temporal.missed_complete_presence_episodes} / {row.temporal.complete_presence_episodes}</td><td>{row.candidate_frames_whole_clips}</td></tr>
}

function Comparison() {
  const [model, setModel] = useState('nanodet'), [interval, setInterval] = useState(5), [mode, setMode] = useState('320px_5hz_peak'), [quality, setQuality] = useState('strict')
  const [data, setData] = useState(null), [error, setError] = useState('')
  useEffect(() => {
    const c = new AbortController(); setData(null); setError('')
    fetch(`/api/evaluation/regions/policies?model=${model}&interval=${interval}&mode=${mode}&quality=${quality}`, { signal: c.signal }).then(async r => {
      const value = await r.json(); if (!r.ok) throw new Error(chineseMessage(value.detail,'策略对照暂时不可用')); return value
    }).then(setData).catch(e => { if (e.name !== 'AbortError') setError(chineseMessage(e.message)) })
    return () => c.abort()
  }, [model, interval, mode, quality])
  return <div className="ev-policy-content"><h2>把漏采、过滤量和候选负载放在一起看</h2><p>仅比较局部观察窗。37 组预设参数、两种检测器和局部运动保护方案；保留原有基线，不自动切换生产配置。</p>
    <div className="ev-report-controls"><label>策略检测器<select aria-label="策略检测器" value={model} onChange={e => setModel(e.target.value)}><option value="nanodet">NanoDet</option><option value="yolox">YOLOX</option></select></label><label>策略候选间隔<select aria-label="策略候选间隔" value={interval} onChange={e => setInterval(Number(e.target.value))}><option value={1}>1 秒</option><option value={5}>5 秒</option></select></label><label>局部运动方案<select aria-label="局部运动方案" value={mode} onChange={e => setMode(e.target.value)}>{Object.entries(modes).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label><label>空景画质条件<select aria-label="空景画质条件" value={quality} onChange={e => setQuality(e.target.value)}><option value="strict">严格条件</option><option value="guarded_night">较宽夜间候选条件</option></select></label></div>
    {error && <FloatingNotice className="ev-error" role="alert">{error}</FloatingNotice>}
    {data ? <><p className="ev-reference-note">1065 张源视频逐帧图、35.5 秒带范围智能参考；仅有两段完整可见片段，尚无人独立抽核，不能解释为生产事件召回率。这里的“漏采”指没有候选帧落在明确可见的核心区间。</p>
      <div className="ev-table-scroll"><table><caption>参数依次为：检测更新 / 连续阴性确认 / 活动保留。候选量覆盖四段完整原片，时间指标仅覆盖复核窗口。</caption><thead><tr><th>参数</th><th>有目标误标空景</th><th>空景标为空景</th><th>有目标误标空景时长</th><th>空景候选时长</th><th>短片段漏采</th><th>全片候选帧</th></tr></thead><tbody><Scores row={data.baseline} label="原基线：5 秒 / 3 次 / 10 秒"/>{data.rows.filter(v => v.policy.hold_mode !== 'legacy').map(v => <Scores key={v.policy_id} row={v}/>)}</tbody></table></div>
      <div className="ev-report-notes"><article><h3>为什么更密集探测还不够</h3><p>局部保护每秒观察 5 次；模型检测仍按表中 1 秒或 5 秒更新。仅增加运动保护可能让采样时刻改变，仍会错过短事件；峰值选帧从最多 0.6 秒的短窗口里额外保留一张，突发窗口启动至少间隔 2 秒。</p></article><article><h3>这些数字分别说明什么</h3><p>快照诊断使用 103 张有目标和 22 张局部空景参考；区间诊断另含较长空景和短暂边缘目标。候选帧增加会给下游带来压力，不能只按过滤时长宣布最优。实际编码、队列、大模型吞吐和实时检测等待尚未验收。</p></article></div>
    </> : !error && <p role="status">正在加载冻结的真实对照结果…</p>}
  </div>
}

export default function PolicyComparison() {
  const [open, setOpen] = useState(false)
  return <details className="ev-policy" onToggle={e => setOpen(e.currentTarget.open)}><summary>策略参数对照 · 试验结果</summary>{open && <Comparison/>}</details>
}
