import {chineseMessage} from './chinese-copy.js'
import {InfoBubble,FloatingNotice} from './Feedback.jsx'
import React,{useEffect,useId,useRef,useState} from 'react'
import {mediaPhases} from './media-job-client.js'
import {operationNames,specialistApi,specialistFailures,validateSpecialistJob,validateSpecialistPage,readSpecialistResult,validateSpecialistSelection,selectionNames,selectionReason,businessStateNames} from './specialist-client.js'
import SpecialistResultEvidence from './SpecialistResultEvidence.jsx'
import {previewFrame} from './analysis-input-client.js'

export default function SpecialistPanel({input,onUnauthenticated,initialJob=null,onNavigateSource,onReviewChanged}){
  const [capabilities,setCapabilities]=useState(null),[page,setPage]=useState(null),[cursors,setCursors]=useState([null]),[operation,setOperation]=useState('text_ocr'),[busy,setBusy]=useState(false),[loading,setLoading]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState(''),[reload,setReload]=useState(0),[selected,setSelected]=useState(()=>initialJob?.state==='completed'?validateSpecialistJob(initialJob,input):null),[result,setResult]=useState(null),[resultError,setResultError]=useState('')
  const selectId=useId(),actions=useRef(null),requests=useRef(new Map()),after=cursors.at(-1)
  const [selection,setSelection]=useState(null),[selectionLoading,setSelectionLoading]=useState(false),[selectionError,setSelectionError]=useState('')
  const [sourcePreview,setSourcePreview]=useState(null),[sourceError,setSourceError]=useState('')
  const previewUrl=sourcePreview?.sha===result?.result_sha256?sourcePreview?.url:null
  const [requestedIntent,setRequestedIntent]=useState('')
  const needsSelection=operation==='license_plate'&&capabilities?.selection_required===true
  const selectionBlocked=needsSelection&&(selectionLoading||!selection||selection.decision!=='selected')
  const profile=capabilities?.bindings?.[operation]?.profile||capabilities?.profile
  function failure(reason,setMessage=setError){if(reason.name!=='AbortError'){setMessage(chineseMessage(reason.message));if(reason.unauthorized)onUnauthenticated()}}
  useEffect(()=>{actions.current=new AbortController();return()=>{actions.current.abort();actions.current=null}},[])
  useEffect(()=>{
    const abort=new AbortController();setLoading(true);setError('');setPage(null)
    Promise.all([specialistApi('specialist-jobs/capabilities',{signal:abort.signal}).catch(failure=>({failure})),specialistApi(`analysis-inputs/${input.input_id}/specialist-jobs?`+new URLSearchParams({limit:10,...(after?{after}:{})}),{signal:abort.signal})]).then(([caps,jobs])=>{
      const checked=validateSpecialistPage(jobs,input,after)
      if(!abort.signal.aborted)setPage(checked)
      if(caps.failure){if(!abort.signal.aborted){setCapabilities(null);failure(caps.failure)};return}
      if(typeof caps.configured!=='boolean'||!Array.isArray(caps.operations)||caps.operations.some(value=>!Object.hasOwn(operationNames,value))||caps.selection_required!==undefined&&typeof caps.selection_required!=='boolean')throw new Error('专用服务状态无效，请重新读取。')
      if(!abort.signal.aborted){setCapabilities(caps);setOperation(value=>caps.operations.includes(value)?value:caps.operations[0]||value)}
    }).catch(reason=>{if(!abort.signal.aborted)failure(reason)}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[input.input_id,after,reload])
  useEffect(()=>{
    const abort=new AbortController();setSelection(null);setSelectionError('');setSelectionLoading(needsSelection)
    if(needsSelection)specialistApi(`analysis-inputs/${input.input_id}/specialist-selection${requestedIntent?'?intent='+requestedIntent:''}`,{signal:abort.signal}).then(value=>validateSpecialistSelection(value,input)).then(value=>{if(!abort.signal.aborted)setSelection(value)}).catch(reason=>{if(!abort.signal.aborted)failure(reason,setSelectionError)}).finally(()=>{if(!abort.signal.aborted)setSelectionLoading(false)})
    return()=>abort.abort()
  },[input.input_id,needsSelection,reload,requestedIntent])
  const pending=page?.items.some(value=>['queued','active'].includes(value.state))
  useEffect(()=>{if(!pending||busy)return;const timer=setInterval(()=>{if(!document.hidden)setReload(n=>n+1)},10000);return()=>clearInterval(timer)},[pending,busy])
  useEffect(()=>{
    const abort=new AbortController();setResult(null);setResultError('')
    if(selected)specialistApi(`specialist-jobs/${selected.job_id}/result`,{signal:abort.signal}).then(value=>readSpecialistResult(value,selected)).then(checked=>{
      if(selected.reused&&(checked.plate_review?.state!=='confirmed'||checked.plate_review.revision!==selected.reuseReviewRevision))throw new Error('引用结果的确认状态已变化，请刷新后重新核验。')
      if(!abort.signal.aborted)setResult(checked)
    }).catch(reason=>{if(!abort.signal.aborted)failure(reason,setResultError)})
    return()=>abort.abort()
  },[selected])
  useEffect(()=>{
    const abort=new AbortController();let temporary=null;setSourcePreview(null);setSourceError('')
    if(result){
      previewFrame({frame_id:result.result.frame_id,registered_payload_sha256:result.result.evidence_sha256},abort.signal).then(async blob=>{
        const decoded=await createImageBitmap(blob)
        const valid=decoded.width===result.result.image_width&&decoded.height===result.result.image_height;decoded.close()
        if(!valid)throw new Error('来源画面尺寸与识别结果不一致。')
        if(!abort.signal.aborted){temporary=URL.createObjectURL(blob);setSourcePreview({sha:result.result_sha256,url:temporary})}
      }).catch(reason=>{if(!abort.signal.aborted)failure(reason,setSourceError)})
    }
    return()=>{abort.abort();if(temporary)URL.revokeObjectURL(temporary)}
  },[result,selected?.reused])
  async function submit(job=null){
    if(busy||!actions.current)return;const abort=actions.current;setBusy(true);setError('');setMessage('')
    if(!job&&selectionBlocked){setBusy(false);setError('当前画面尚未被选择用于专用分析。');return}
    const key=job?`retry:${job.job_id}:${job.revision}`:`create:${operation}:${profile}:${requestedIntent}`
    if(!requests.current.has(key))requests.current.set(key,crypto.randomUUID())
    const request_id=requests.current.get(key),path=job?`specialist-jobs/${job.job_id}/retry`:`analysis-inputs/${input.input_id}/specialist-jobs`
    try{const value=validateSpecialistJob(await specialistApi(path,{method:'POST',signal:abort.signal,body:JSON.stringify(job?{request_id,revision:job.revision}:{request_id,input_manifest_sha256:input.manifest_sha256,operation,...(operation==='license_plate'&&requestedIntent?{requested_intent:requestedIntent}:{})})}),input)
      if(job&&value.job_id!==job.job_id)throw new Error('恢复的任务编号不一致。')
      if(!abort.signal.aborted){setMessage(value.dispatch_state==='paused'?'任务已登记，等待恢复处理。':value.state==='completed'?'已有对应结果，可在下方查看。':'任务已登记，可查看后续进度。');setReload(n=>n+1)}
    }catch(reason){if(!abort.signal.aborted)failure(reason)}finally{if(!abort.signal.aborted)setBusy(false)}
  }
  async function viewExisting(){
    const reference=selection?.reuse_reference;if(!reference||busy||!actions.current)return
    const abort=actions.current;setBusy(true);setError('')
    try{const value=await specialistApi(`specialist-jobs/${reference.job_id}`,{signal:abort.signal})
      const source={input_id:reference.input_id,anchor_frame_id:reference.frame_id,camera_id:input.camera_id,manifest_sha256:reference.input_manifest_sha256}
      const checked=validateSpecialistJob(value,source)
      if(checked.profile!==reference.profile||checked.state!=='completed')throw new Error('引用结果已变化，请重新核验。')
      if(!abort.signal.aborted)setSelected({...checked,reused:true,reuseReviewRevision:reference.revision})
    }catch(reason){if(!abort.signal.aborted)failure(reason)}finally{if(!abort.signal.aborted)setBusy(false)}
  }
  return <section className="evidence-context specialist-panel" aria-label="专用文字与车牌识别"><div className="context-heading"><h3 className="help-heading">专用识别<InfoBubble title="专用识别说明"><p className="result-note">使用保存的原始单帧。结果是待核验辅助信息，不自动归入当前车辆或写入搜索索引。</p>{capabilities&&!capabilities.configured&&<p>专用识别服务尚未配置，暂不能提交任务。</p>}</InfoBubble></h3><button type="button" className="text-button" disabled={loading||busy} onClick={()=>setReload(n=>n+1)}>刷新识别任务</button></div>
    
    
    <div className="context-heading"><label htmlFor={selectId}>识别类型</label><select id={selectId} value={operation} disabled={busy||!capabilities?.configured} onChange={event=>setOperation(event.target.value)}>{Object.entries(operationNames).map(([value,label])=><option key={value} value={value} disabled={capabilities&&!capabilities.operations.includes(value)}>{label}</option>)}</select><button type="button" className="admin-button secondary" disabled={busy||loading||!capabilities?.configured||selectionBlocked||!capabilities.operations.includes(operation)||input.source_status==='stale'} onClick={()=>submit()}>{busy?'正在提交…':'提交专用识别'}</button></div>
    {operation==='license_plate'&&<div className="context-heading"><label htmlFor={selectId+'-intent'}>识别目的</label><select id={selectId+'-intent'} disabled={busy} value={requestedIntent} onChange={event=>setRequestedIntent(event.target.value)}><option value="">按自动选择依据处理</option><option value="verify_vehicle">主动核验当前车辆</option><option value="plate_query">查询当前车辆的车牌</option></select><InfoBubble title="主动申请说明">仅对当前保存输入中的车辆尝试识别。人物、缺失目标框或过小车辆不会放行。结果需要核对当前原图，不能据此自动认定跨画面身份。</InfoBubble></div>}
    {selectionLoading&&<p role="status">正在核验专用分析选择依据…</p>}{selectionError&&<FloatingNotice className="notice error" role="alert">{selectionError}</FloatingNotice>}
    {selection&&<section aria-label="专用分析选择"><h4 className="help-heading">{selectionNames[selection.decision]}<InfoBubble title="使用与限制说明"><p className="result-note">本选择只影响专用分析，原有语义分析继续保留；推荐不表示已经识别完成。</p></InfoBubble></h4>{selection.business_state&&<p>{businessStateNames[selection.business_state]}</p>}<p>{selection.reasons.map(selectionReason).join('；')}</p>{selection.decision==='reused'&&<button className="admin-button secondary" type="button" disabled={busy} onClick={viewExisting}>查看原帧确认结果</button>}</section>}
    {loading&&<p role="status">正在读取专用任务…</p>}{error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{message&&<FloatingNotice role="status" className="admin-success">{message}</FloatingNotice>}
    {page&&<><ul className="media-job-items">{page.items.map(job=><li key={job.job_id}><div><strong>{operationNames[job.operation]} · {mediaPhases[job.dispatch_state]}</strong><p className="admin-muted">{job.job_id.slice(0,8)} · {job.profile} · 失败 {job.attempts} 次</p></div><div className="admin-row-actions">{job.last_error_code&&<InfoBubble title="查看警告说明" className="error">{specialistFailures[job.last_error_code]||'专用任务执行异常'}</InfoBubble>}{job.state==='failed'&&<button type="button" className="admin-button secondary" disabled={busy} onClick={()=>submit(job)}>重试专用任务</button>}{job.state==='completed'&&<button type="button" className="admin-button secondary" onClick={()=>setSelected(selected?.job_id===job.job_id?null:job)}>查看识别结果</button>}</div></li>)}</ul>{!page.items.length&&<p className="result-note">暂无专用识别任务。</p>}<div className="admin-list-paging"><button type="button" className="text-button" disabled={busy||loading||cursors.length===1} onClick={()=>setCursors(values=>values.slice(0,-1))}>上一页识别任务</button><span>第 {cursors.length} 页</span><button type="button" className="text-button" disabled={busy||loading||!page.has_more} onClick={()=>setCursors(values=>[...values,page.next_cursor])}>下一页识别任务</button></div></>}
    {selected&&!result&&!resultError&&<p role="status">正在核验识别结果…</p>}{resultError&&<FloatingNotice className="notice error" role="alert">{resultError}<button className="text-button" type="button" onClick={()=>setSelected(value=>({...value}))}>重新读取识别结果</button></FloatingNotice>}
    {result&&selected?.job_id===result.job_id&&<SpecialistResultEvidence key={result.job_id+':'+result.result_sha256} job={selected} artifact={result} previewUrl={previewUrl} sourceError={sourceError} onRetrySource={()=>setResult(value=>({...value}))} onUnauthenticated={onUnauthenticated} onNavigateSource={onNavigateSource} onUpdated={()=>{onReviewChanged?.();setSelected(value=>({...value}));setReload(value=>value+1)}}/>}
  </section>
}
