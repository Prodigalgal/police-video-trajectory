import React,{useEffect,useRef,useState} from 'react'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {chineseMessage} from './chinese-copy.js'
import {formatTime} from './result-format.js'
import {taskPhase} from './task-status.js'
import SpecialistPanel from './SpecialistPanel.jsx'

export default function SpecialistControlPanel({refresh,onError,request}){
  const [data,setData]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState(''),[reload,setReload]=useState(0),[selected,setSelected]=useState(null)
  const report=useRef(onError);report.current=onError
  useEffect(()=>{
    const abort=new AbortController();let pending=false
    async function load(){if(pending)return;pending=true;try{const value=await request('specialist-control',{signal:abort.signal});if(!abort.signal.aborted)setData(value)}catch(failure){if(!abort.signal.aborted){setError(chineseMessage(failure.message));if(failure.unauthorized)report.current(failure)}}finally{pending=false}}
    load();const timer=setInterval(()=>{if(!document.hidden)load()},10000)
    return()=>{abort.abort();clearInterval(timer)}
  },[refresh,reload,request])
  async function control(){
    setBusy(true);setError('');setMessage('')
    try{await request('specialist-control',{method:'POST',body:JSON.stringify({enabled:!data.enabled,expected_revision:data.revision})});setMessage(data.enabled?'车牌消费已暂停，在途任务继续完成。':'车牌消费已恢复，主分析状态保持不变。');setReload(value=>value+1)}catch(failure){setError(failure.code==='specialist_control_conflict'?'消费开关已变化，请刷新后再操作。':chineseMessage(failure.message));if(failure.unauthorized)report.current(failure)}finally{setBusy(false)}
  }
  return <section className="admin-card"><div className="admin-card-heading"><h2>车牌消费与结果</h2><InfoBubble title="独立消费说明">车牌消费独立于主分析。自动候选随主分析状态推进；用户可在已入库车辆的单帧输入中主动申请核验。这里只显示最近 20 个来源仍有效的任务，原图、识别结果和复核沿用同一来源校验。</InfoBubble></div>
    {error&&<FloatingNotice role="alert" className="error" onDismiss={()=>setError('')}>{error}</FloatingNotice>}{message&&<FloatingNotice role="status" onDismiss={()=>setMessage('')}>{message}</FloatingNotice>}
    {!data?<p role="status">正在读取车牌消费…</p>:<><p className="admin-muted">专用消费{data.enabled?'已开启':'已暂停'} · 主分析{data.main_analysis_enabled?'已开启':'已暂停'}</p><div className="admin-form-actions"><button className="admin-button" disabled={busy} onClick={control}>{busy?'正在更新…':data.enabled?'暂停车牌消费':'恢复车牌消费'}</button><button className="admin-button secondary" disabled={busy} onClick={()=>setReload(value=>value+1)}>刷新任务</button></div>
      <div className="admin-model-grid">{data.counts.map(value=><p key={value.state}>{({queued:'排队',active:'处理',completed:'完成',failed:'失败'})[value.state]||'其他状态'}：{value.count}</p>)}</div>
      {data.recent.length?<ul className="admin-job-list">{data.recent.map(entry=><li key={entry.job.job_id}><div><strong>{entry.job.camera_id.replace('recording-','点位 ')} · {taskPhase(entry.job,!data.enabled).label}</strong><p className="admin-muted">{formatTime(entry.job.created_at)} · {entry.job.profile}</p></div><button className="admin-button secondary" onClick={()=>setSelected(entry)}>查看任务与结果</button></li>)}</ul>:<p className="admin-empty">暂无已登记的车牌任务。</p>}</>}
    {selected&&<section><div className="admin-card-heading"><h3>当前任务详情</h3><button className="admin-button secondary" onClick={()=>setSelected(null)}>收起详情</button></div><SpecialistPanel key={selected.job.job_id} input={selected.input} initialJob={selected.job} onUnauthenticated={()=>onError({unauthorized:true})}/></section>}
  </section>
}
