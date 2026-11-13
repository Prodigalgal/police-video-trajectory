import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {downloadInput,inputApi,validateContext,validateInput,validateInputPage} from './analysis-input-client.js'
import SpecialistPanel from './SpecialistPanel.jsx'

const contextWindow={before_ms:60000,after_ms:60000,max_frames:16,max_gap_ms:6000}

export default function TemporalContextPanel({detail,digest,onUnauthenticated,onNavigateSource}){
  const [enabled,setEnabled]=useState(null),[featureRetry,setFeatureRetry]=useState(0),[featureError,setFeatureError]=useState('')
  const [context,setContext]=useState(null),[loading,setLoading]=useState(false),[contextError,setContextError]=useState(''),[reload,setReload]=useState(0)
  const [page,setPage]=useState(null),[cursors,setCursors]=useState([null]),[listError,setListError]=useState(''),[listLoading,setListLoading]=useState(false),[listReload,setListReload]=useState(0)
  const [busy,setBusy]=useState(false),[actionError,setActionError]=useState(''),[message,setMessage]=useState('')
  const requestIds=useRef(new Map()),actions=useRef(new AbortController())
  const [specialistsEnabled,setSpecialistsEnabled]=useState(false),[specialistInput,setSpecialistInput]=useState(null)
  const after=cursors[cursors.length-1]
  function failed(error,setError){if(error.name==='AbortError')return;setError(chineseMessage(error.message));if(error.unauthorized)onUnauthenticated()}
  useEffect(()=>{actions.current=new AbortController();return()=>actions.current.abort()},[])
  useEffect(()=>{
    const abort=new AbortController();setFeatureError('')
    fetch('/api/v1/features',{signal:abort.signal,cache:'no-store'}).then(async response=>{
      if(response.status===401){onUnauthenticated();return}
      if(!response.ok)throw new Error('暂时无法读取分析输入功能状态。')
      const value=await response.json();if(!abort.signal.aborted){setEnabled(value.analysis_inputs===true);setSpecialistsEnabled(value.specialist_jobs===true)}
    }).catch(error=>{if(!abort.signal.aborted)setFeatureError(chineseMessage(error.message))})
    return()=>abort.abort()
  },[featureRetry])
  useEffect(()=>{
    if(!enabled)return
    const abort=new AbortController();setLoading(true);setContext(null);setContextError('')
    const params=new URLSearchParams({...contextWindow,expected_version:detail.record_version})
    inputApi(`observations/${detail.observation_id}/context?${params}`,{signal:abort.signal}).then(value=>{
      const checked=validateContext(value,detail,contextWindow);if(!abort.signal.aborted)setContext(checked)
    }).catch(error=>{if(!abort.signal.aborted)failed(error,setContextError)}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[enabled,reload,detail.observation_id,detail.record_version])
  useEffect(()=>{
    if(!enabled)return
    const abort=new AbortController();setListLoading(true);setListError('');setPage(null)
    const params=new URLSearchParams({observation_id:detail.observation_id,limit:10,...(after?{after}:{})})
    inputApi(`analysis-inputs?${params}`,{signal:abort.signal}).then(value=>{const checked=validateInputPage(value,detail.observation_id);if(!abort.signal.aborted)setPage(checked)}).catch(error=>{if(!abort.signal.aborted)failed(error,setListError)}).finally(()=>{if(!abort.signal.aborted)setListLoading(false)})
    return()=>abort.abort()
  },[enabled,detail.observation_id,after,listReload])
  async function save(purpose){
    setBusy(true);setActionError('');setMessage('')
    const body={observation_id:detail.observation_id,observation_version:detail.record_version,evidence_sha256:detail.evidence.sha256,purpose,...(purpose==='frame_sequence'?{window:contextWindow}:{})}
    const key=JSON.stringify(body)
    try{
      if(!requestIds.current.has(key))requestIds.current.set(key,crypto.randomUUID())
      const value=await inputApi('analysis-inputs',{method:'POST',signal:actions.current.signal,body:JSON.stringify({...body,request_id:requestIds.current.get(key)})})
      validateInput(value,detail.observation_id)
      if(!actions.current.signal.aborted){setMessage('分析输入已保存。本操作不发起模型请求。');setCursors([null]);setListReload(n=>n+1)}
    }catch(error){if(!actions.current.signal.aborted)failed(error,setActionError)}finally{if(!actions.current.signal.aborted)setBusy(false)}
  }
  async function download(input){
    setBusy(true);setActionError('')
    try{const blob=await downloadInput(input,actions.current.signal);if(actions.current.signal.aborted)return;const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=`analysis-input-${input.input_id}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}catch(error){if(!actions.current.signal.aborted)failed(error,setActionError)}finally{if(!actions.current.signal.aborted)setBusy(false)}
  }
  if(featureError)return <FloatingNotice className="notice error" role="alert">{featureError}<button type="button" className="text-button" onClick={()=>setFeatureRetry(n=>n+1)}>重试</button></FloatingNotice>
  if(!enabled)return null
  return <section className="evidence-context" aria-label="分析输入">
    {loading&&<p role="status">正在读取采样序列状态…</p>}{contextError&&<FloatingNotice className="notice error" role="alert">{contextError}</FloatingNotice>}
    <div className="context-heading"><h3>分析输入</h3><button type="button" className="text-button" disabled={loading||busy} onClick={()=>setReload(n=>n+1)}>重新读取</button><button type="button" className="admin-button secondary" disabled={busy||digest!=='valid'} onClick={()=>save('single_frame')}>保存当前单帧</button><button type="button" className="admin-button secondary" disabled={busy||digest!=='valid'||!context?.sequence.eligible} onClick={()=>save('frame_sequence')}>保存采样序列</button></div>
    {digest!=='valid'&&<p className="result-note">完成上方原图摘要核验后可保存输入。</p>}{message&&<FloatingNotice role="status" className="admin-success">{message}</FloatingNotice>}{actionError&&<FloatingNotice className="notice error" role="alert">{actionError}</FloatingNotice>}
    {listLoading&&<p role="status">正在读取已保存输入…</p>}{listError&&<FloatingNotice className="notice error" role="alert">{listError}<button type="button" className="text-button" onClick={()=>setListReload(n=>n+1)}>重试</button></FloatingNotice>}
    {page&&<><ul className="context-input-list">{page.items.map(input=><li key={input.input_id}><span>{input.purpose==='single_frame'?'单帧':'采样序列'} · {input.input_id.slice(0,8)}<small>{input.source_status==='stale'?'源记录已更新':'导出时重新核验画面'}</small></span><div className="admin-row-actions"><button type="button" className="text-button" disabled={busy||input.source_status==='stale'} onClick={()=>download(input)}>下载输入清单</button>{specialistsEnabled&&input.purpose==='single_frame'&&<button type="button" className="text-button" disabled={busy||input.source_status==='stale'} onClick={()=>setSpecialistInput(specialistInput?.input_id===input.input_id?null:input)}>专用文字与车牌识别</button>}</div></li>)}</ul>{!page.items.length&&<p className="result-note">暂无已保存输入。</p>}<div className="context-heading"><button type="button" className="text-button" disabled={busy||listLoading||cursors.length===1} onClick={()=>setCursors(values=>values.slice(0,-1))}>上一页</button><button type="button" className="text-button" disabled={busy||listLoading||!page.has_more} onClick={()=>setCursors(values=>[...values,page.next_cursor])}>下一页</button></div></>}
    {specialistsEnabled&&specialistInput&&<SpecialistPanel key={specialistInput.input_id} input={specialistInput} onUnauthenticated={onUnauthenticated} onNavigateSource={onNavigateSource}/>}
  </section>
}
