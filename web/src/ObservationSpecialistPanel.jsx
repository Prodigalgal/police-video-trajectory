import React,{useEffect,useRef,useState} from 'react'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {chineseMessage} from './chinese-copy.js'
import {formatTime} from './result-format.js'
import {mediaPhases} from './media-job-client.js'
import {operationNames,specialistApi,specialistFailures} from './specialist-client.js'
import {specialistCursor,validateObservationSpecialists} from './observation-specialists.js'
import SpecialistPanel from './SpecialistPanel.jsx'

export default function ObservationSpecialistPanel({detail,onUnauthenticated,onNavigateSource}){
  const [open,setOpen]=useState(false),[page,setPage]=useState(null),[cursors,setCursors]=useState([null])
  const [loading,setLoading]=useState(false),[error,setError]=useState(''),[refresh,setRefresh]=useState(0),[selected,setSelected]=useState(null)
  const report=useRef(onUnauthenticated);report.current=onUnauthenticated
  const after=cursors.at(-1)
  useEffect(()=>{
    if(!open)return
    const abort=new AbortController();setLoading(true);setError('');setPage(null)
    const query=new URLSearchParams({observation_version:detail.record_version,limit:10,...(after?{after:after.job_id}:{})})
    specialistApi(`observations/${detail.observation_id}/specialist-jobs?${query}`,{signal:abort.signal})
      .then(value=>validateObservationSpecialists(value,detail,after))
      .then(value=>{if(!abort.signal.aborted){setPage(value);setSelected(current=>value.items.find(entry=>entry.job.job_id===current?.job.job_id)?.input.source_status==='stale'?null:current)}})
      .catch(failure=>{if(!abort.signal.aborted){setError(chineseMessage(failure));if(['observation_revision_conflict','observation_unavailable','specialist_input_changed'].includes(failure.code))setSelected(null);if(failure.unauthorized)report.current()}})
      .finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[open,detail.observation_id,detail.record_version,detail.frame_id,detail.camera_id,after,refresh])
  const pending=page?.items.some(({job})=>['queued','active'].includes(job.state))
  useEffect(()=>{
    if(!open||!pending)return
    const timer=setInterval(()=>{if(!document.hidden)setRefresh(value=>value+1)},10000)
    return()=>clearInterval(timer)
  },[open,pending])
  function select(entry){setSelected(value=>value?.job.job_id===entry.job.job_id?null:entry)}
  function navigate(next){setSelected(null);setCursors(next)}
  return <section className="evidence-context" aria-label="本线索专用任务与结果"><div className="context-heading">
    <h3 className="help-heading">专用识别任务与结果<InfoBubble title="结果来源说明">只列出本线索原帧保存输入对应的任务；展开不会自动创建输入或调用模型。牌号和文字需要结合原图复核，不自动作为当前车辆属性或跨点位身份。旧版本输入只展示历史任务状态。</InfoBubble></h3>
    <button type="button" className="text-button" aria-expanded={open} onClick={()=>{setOpen(value=>!value);setSelected(null)}}>{open?'收起专用任务':'查看专用任务与结果'}</button>
  </div>{open&&<>
    <div className="context-heading"><span>按创建时间从新到旧排列</span><button type="button" className="text-button" disabled={loading} onClick={()=>setRefresh(value=>value+1)}>刷新任务状态</button></div>
    {loading&&<p role="status">正在读取本线索的专用任务…</p>}
    {error&&<FloatingNotice role="alert" className="notice error">{error}<button type="button" className="text-button" onClick={()=>setRefresh(value=>value+1)}>重新读取任务</button></FloatingNotice>}
    {page&&<><ul className="media-job-items">{page.items.map(entry=><li key={entry.job.job_id}><div><strong>{operationNames[entry.job.operation]} · {mediaPhases[entry.job.dispatch_state]}</strong><p className="admin-muted">{formatTime(entry.job.created_at)} · {entry.job.job_id.slice(0,8)}</p></div><div className="admin-row-actions">
      {entry.job.last_error_code&&<InfoBubble title="任务失败说明">{specialistFailures[entry.job.last_error_code]||'任务执行异常，请查看识别任务。'}</InfoBubble>}
      {entry.input.source_status==='stale'?<><span className="admin-badge">旧版本输入</span><InfoBubble title="旧版本来源">该输入来自线索的旧版本，不能当作当前结果继续复核。请使用当前原图重新保存输入。</InfoBubble></>:<button className="text-button" type="button" onClick={()=>select(entry)}>{selected?.job.job_id===entry.job.job_id?'收起识别详情':entry.job.state==='completed'?'查看识别结果':'查看识别任务'}</button>}
    </div></li>)}</ul>{!page.items.length&&<p className="result-note">本线索尚无已登记的专用任务，可在保存原始单帧后按需提交。</p>}
    <div className="admin-list-paging"><button type="button" className="text-button" disabled={loading||cursors.length===1} onClick={()=>navigate(values=>values.slice(0,-1))}>上一页专用任务</button><span>第 {cursors.length} 页</span><button type="button" className="text-button" disabled={loading||!page.has_more} onClick={()=>navigate(values=>[...values,specialistCursor(page.items.at(-1))])}>下一页专用任务</button></div></>}
    {selected&&<SpecialistPanel key={selected.job.job_id} input={selected.input} initialJob={selected.job} onUnauthenticated={onUnauthenticated} onNavigateSource={onNavigateSource}/>}
  </>}</section>
}
