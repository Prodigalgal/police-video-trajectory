import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React,{useEffect,useMemo,useRef,useState} from 'react'
import {formatTime} from './result-format.js'
import {candidateStates,candidatePhases,candidateReason,validateCandidate,validateCandidatePage} from './specialist-candidate-client.js'

export default function SpecialistCandidatesPanel({cameras,refresh,onError,request}){
  const [camera,setCamera]=useState(''),[state,setState]=useState(''),[cursors,setCursors]=useState([null]),[page,setPage]=useState(null),[loading,setLoading]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState(''),[reload,setReload]=useState(0),[busy,setBusy]=useState(false),[reason,setReason]=useState('')
  const after=cursors.at(-1),scope=JSON.stringify([camera,state,after])
  const allowed=useMemo(()=>new Set(cameras.map(value=>value.camera_id)),[cameras])
  const actions=useRef(null),requests=useRef(new Map()),reportError=useRef(onError)
  useEffect(()=>{reportError.current=onError},[onError])
  useEffect(()=>{const abort=new AbortController();actions.current=abort;return()=>{abort.abort();actions.current=null}},[scope])
  useEffect(()=>{
    const abort=new AbortController();setPage(null);setError('');setLoading(allowed.size>0)
    if(allowed.size){
      const params=new URLSearchParams({limit:20,...(camera?{camera_id:camera}:{}),...(state?{state}:{}),...(after?{after}:{})})
      request('specialist-candidates?'+params,{signal:abort.signal}).then(value=>validateCandidatePage(value,allowed,{camera,state,after}))
        .then(value=>{if(!abort.signal.aborted)setPage({scope,value})})
        .catch(failure=>{if(!abort.signal.aborted){setError(chineseMessage(failure.message));reportError.current(failure)}})
        .finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    }
    return()=>abort.abort()
  },[allowed,camera,state,after,scope,refresh,reload,request])
  const current=page?.scope===scope?page.value:null
  const pending=current?.items.some(value=>['ready','active','waiting_source','waiting_configuration','waiting_budget','waiting_result','waiting_review'].includes(value.state))
  useEffect(()=>{if(!pending||busy||!current?.enabled||!current.claims_enabled)return;const timer=setInterval(()=>{if(!document.hidden)setReload(value=>value+1)},10000);return()=>clearInterval(timer)},[pending,busy,current?.enabled,current?.claims_enabled])
  function change(update){setPage(null);setMessage('');setCursors([null]);update()}
  async function recover(candidate){
    if(busy||!reason.trim()||!actions.current)return
    const abort=actions.current;setBusy(true);setError('');setMessage('')
    const key=[candidate.candidate_id,candidate.revision,reason.trim()].join(':')
    if(!requests.current.has(key))requests.current.set(key,crypto.randomUUID())
    try{
      const value=validateCandidate(await request(`specialist-candidates/${candidate.candidate_id}/retry`,{method:'POST',signal:abort.signal,body:JSON.stringify({request_id:requests.current.get(key),expected_revision:candidate.revision,reason:reason.trim()})}),allowed)
      if(value.candidate_id!==candidate.candidate_id||value.frame_id!==candidate.frame_id||value.camera_id!==candidate.camera_id||value.state!=='ready'||value.revision!==candidate.revision+1)throw new Error('恢复回执不一致，请重新读取候选。')
      if(!abort.signal.aborted){setMessage('恢复已登记，仍需经过来源与预算核验。');setReload(value=>value+1)}
    }catch(failure){if(!abort.signal.aborted){setError(chineseMessage(failure.message));reportError.current(failure)}}
    finally{if(!abort.signal.aborted)setBusy(false)}
  }
  return <><div className="admin-section-title"><div><h1>车牌候选</h1><p>查看自动提交进度、等待原因和来源引用。</p></div></div>
    <section className="admin-card" aria-label="车牌候选提交">
      <div className="admin-filters"><label>监控点位<select aria-label="监控点位" value={camera} disabled={busy} onChange={event=>change(()=>setCamera(event.target.value))}><option value="">全部点位</option>{cameras.map(value=><option key={value.camera_id} value={value.camera_id}>{value.display_name}</option>)}</select></label>
        <label>候选状态<select aria-label="候选状态" value={state} disabled={busy} onChange={event=>change(()=>setState(event.target.value))}><option value="">全部状态</option>{Object.entries(candidateStates).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></label>
        <button type="button" className="admin-button secondary" disabled={busy||loading} onClick={()=>setReload(value=>value+1)}>刷新候选</button></div>
      {!cameras.length&&<p className="admin-empty">暂无可管理点位。</p>}
      {current&&<p className="result-note">{!current.enabled?'自动提交尚未启用，已有记录仍可查看。':!current.claims_enabled?'处理已暂停，候选等待恢复。':'自动提交已启用。'} 已登记专用任务不表示车牌识别成功；结果引用不赋予另一帧车辆身份。</p>}
      {loading&&<p role="status">正在读取车牌候选…</p>}{error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{message&&<FloatingNotice className="admin-success" role="status">{message}</FloatingNotice>}
      {busy&&<p role="status">正在登记恢复…</p>}
      {current?.items.some(value=>value.can_retry)&&<label className="candidate-recovery-reason">恢复说明<input aria-label="恢复说明" value={reason} maxLength={300} disabled={busy} onChange={event=>setReason(event.target.value)} placeholder="说明依赖恢复或重新提交的原因"/></label>}
      {current&&<><ul className="media-job-items candidate-items">{current.items.map(candidate=><li key={candidate.candidate_id}><div><strong>{candidatePhases[candidate.phase]}</strong><p>{candidateReason(candidate.reason)}</p><p className="admin-muted">{cameras.find(value=>value.camera_id===candidate.camera_id)?.display_name||candidate.camera_id} · 来源帧 {candidate.frame_id.slice(0,8)} · 提交故障 {candidate.failures} 次</p>
        <p className="admin-muted">更新 {formatTime(candidate.updated_at)}{['ready','waiting_source','waiting_configuration','waiting_budget','waiting_result','waiting_review'].includes(candidate.state)?` · 下次核验 ${formatTime(candidate.next_attempt_at)}`:''}</p>
        {candidate.job_id&&<p className="admin-muted">专用任务 {candidate.job_id.slice(0,8)}</p>}{candidate.reference_job_id&&<p className="admin-muted">原结果引用 {candidate.reference_job_id.slice(0,8)}</p>}</div>
        {candidate.can_retry&&<button type="button" className="admin-button secondary" disabled={busy||!reason.trim()} onClick={()=>recover(candidate)}>恢复候选提交</button>}</li>)}</ul>
        {!current.items.length&&<p className="admin-empty">暂无符合条件的车牌候选。</p>}
        <div className="admin-list-paging"><button type="button" className="admin-button secondary" disabled={busy||loading||cursors.length===1} onClick={()=>setCursors(value=>value.slice(0,-1))}>上一页候选</button><span>第 {cursors.length} 页</span><button type="button" className="admin-button secondary" disabled={busy||loading||!current.has_more} onClick={()=>setCursors(value=>[...value,current.next_cursor])}>下一页候选</button></div></>}
    </section></>
}
