import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {downloadInput,inputApi,previewFrame,validateContext,validateInput,validateInputPage} from './analysis-input-client.js'
import SpecialistPanel from './SpecialistPanel.jsx'
import NearbyRecordsPanel from './NearbyRecordsPanel.jsx'

const sequenceReasons={clock_unavailable:'此帧没有可核验的片内时钟，支持保存单帧。',ambiguous_time:'同一片内时间有多帧，暂不组成序列。',insufficient_nearby_frames:'附近没有间隔在 6 秒内的其他关键帧，支持保存单帧。',sampled_sequence:'可保存当前帧所在的采样序列。采样画面不代表连续视频或同一目标。'}

export default function TemporalContextPanel({detail,digest,onUnauthenticated,onNavigateSource}){
  const [enabled,setEnabled]=useState(null),[featureRetry,setFeatureRetry]=useState(0),[featureError,setFeatureError]=useState('')
  const [seconds,setSeconds]=useState(60),[context,setContext]=useState(null),[loading,setLoading]=useState(false),[contextError,setContextError]=useState(''),[reload,setReload]=useState(0)
  const [selected,setSelected]=useState(null),[preview,setPreview]=useState(null),[previewError,setPreviewError]=useState(''),[previewLoading,setPreviewLoading]=useState(false)
  const [page,setPage]=useState(null),[cursors,setCursors]=useState([null]),[listError,setListError]=useState(''),[listLoading,setListLoading]=useState(false),[listReload,setListReload]=useState(0)
  const [busy,setBusy]=useState(false),[actionError,setActionError]=useState(''),[message,setMessage]=useState('')
  const requestIds=useRef(new Map()),actions=useRef(new AbortController())
  const [specialistsEnabled,setSpecialistsEnabled]=useState(false),[specialistInput,setSpecialistInput]=useState(null)
  const after=cursors[cursors.length-1]
  const window={before_ms:seconds*1000,after_ms:seconds*1000,max_frames:16,max_gap_ms:6000}
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
    const abort=new AbortController();setLoading(true);setContext(null);setContextError('');setSelected(null)
    const params=new URLSearchParams({...window,expected_version:detail.record_version})
    inputApi(`observations/${detail.observation_id}/context?${params}`,{signal:abort.signal}).then(value=>{
      const checked=validateContext(value,detail,window);if(!abort.signal.aborted)setContext(checked)
    }).catch(error=>{if(!abort.signal.aborted)failed(error,setContextError)}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[enabled,seconds,reload,detail.observation_id,detail.record_version])
  useEffect(()=>{
    if(!enabled)return
    const abort=new AbortController();setListLoading(true);setListError('');setPage(null)
    const params=new URLSearchParams({observation_id:detail.observation_id,limit:10,...(after?{after}:{})})
    inputApi(`analysis-inputs?${params}`,{signal:abort.signal}).then(value=>{const checked=validateInputPage(value,detail.observation_id);if(!abort.signal.aborted)setPage(checked)}).catch(error=>{if(!abort.signal.aborted)failed(error,setListError)}).finally(()=>{if(!abort.signal.aborted)setListLoading(false)})
    return()=>abort.abort()
  },[enabled,detail.observation_id,after,listReload])
  useEffect(()=>{
    const abort=new AbortController();let url;setPreview(null);setPreviewError('');setPreviewLoading(false)
    if(selected){setPreviewLoading(true);previewFrame(selected,abort.signal).then(blob=>{if(!abort.signal.aborted){url=URL.createObjectURL(blob);setPreview(url)}}).catch(error=>{if(!abort.signal.aborted)failed(error,setPreviewError)}).finally(()=>{if(!abort.signal.aborted)setPreviewLoading(false)})}
    return()=>{abort.abort();if(url)URL.revokeObjectURL(url)}
  },[selected])
  async function save(purpose){
    setBusy(true);setActionError('');setMessage('')
    const body={observation_id:detail.observation_id,observation_version:detail.record_version,evidence_sha256:detail.evidence.sha256,purpose,...(purpose==='frame_sequence'?{window}:{})}
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
  const anchor=context?.frames.find(frame=>frame.frame_id===detail.frame_id)
  const recordedOnly=context?.sequence.reason==='clock_unavailable'
  return <section className="evidence-context" aria-label="附近画面与分析输入">
    <div className="context-heading"><h3>{recordedOnly?'辅助留存关键帧':'同段采样关键帧'}</h3>{!recordedOnly&&<label>前后窗口 <select value={seconds} onChange={event=>setSeconds(Number(event.target.value))} disabled={busy}>{[15,60,120].map(n=><option key={n} value={n}>{n} 秒</option>)}</select></label>}<button type="button" className="text-button" disabled={loading||busy} onClick={()=>setReload(n=>n+1)}>重新读取</button></div>
    {loading&&<p role="status">正在读取附近关键帧…</p>}{contextError&&<FloatingNotice className="notice error" role="alert">{contextError}</FloatingNotice>}
    {recordedOnly&&<NearbyRecordsPanel detail={detail} refresh={reload} onUnauthenticated={onUnauthenticated} onNavigate={onNavigateSource}/>}
    {context&&!recordedOnly&&<><div className="context-frames" role="group" aria-label="选择同段关键帧">{context.frames.map(frame=><button key={frame.frame_id} type="button" className="admin-button secondary" aria-pressed={selected?.frame_id===frame.frame_id} onClick={()=>setSelected(frame.frame_id===detail.frame_id?null:frame)}>{frame.frame_id===detail.frame_id?'当前帧':`${((frame.relative_media_ms-anchor.relative_media_ms)/1000).toFixed(1)} 秒`}{frame.ambiguous_time?' · 时间重复':''}</button>)}</div>
    <p className="result-note">{sequenceReasons[context.sequence.reason]}{context.truncated?' 已达到本次 16 帧上限。':''}</p></>}
    {previewLoading&&<p role="status">正在校验所选画面…</p>}{previewError&&<FloatingNotice className="notice error" role="alert">{previewError}</FloatingNotice>}
    {preview&&<figure className="context-preview"><img src={preview} alt="所选同段邻近关键帧" onError={()=>setPreviewError('浏览器无法显示所选画面。')}/><figcaption>邻近关键帧仅供场景参考，当前线索的属性与定位框仍对应上方原图。</figcaption></figure>}
    <div className="context-heading"><h3>分析输入</h3><button type="button" className="admin-button secondary" disabled={busy||digest!=='valid'} onClick={()=>save('single_frame')}>保存当前单帧</button><button type="button" className="admin-button secondary" disabled={busy||digest!=='valid'||!context?.sequence.eligible} onClick={()=>save('frame_sequence')}>保存采样序列</button></div>
    {digest!=='valid'&&<p className="result-note">完成上方原图摘要核验后可保存输入。</p>}{message&&<FloatingNotice role="status" className="admin-success">{message}</FloatingNotice>}{actionError&&<FloatingNotice className="notice error" role="alert">{actionError}</FloatingNotice>}
    {listLoading&&<p role="status">正在读取已保存输入…</p>}{listError&&<FloatingNotice className="notice error" role="alert">{listError}<button type="button" className="text-button" onClick={()=>setListReload(n=>n+1)}>重试</button></FloatingNotice>}
    {page&&<><ul className="context-input-list">{page.items.map(input=><li key={input.input_id}><span>{input.purpose==='single_frame'?'单帧':'采样序列'} · {input.input_id.slice(0,8)}<small>{input.source_status==='stale'?'源记录已更新':'导出时重新核验画面'}</small></span><div className="admin-row-actions"><button type="button" className="text-button" disabled={busy||input.source_status==='stale'} onClick={()=>download(input)}>下载输入清单</button>{specialistsEnabled&&input.purpose==='single_frame'&&<button type="button" className="text-button" disabled={busy||input.source_status==='stale'} onClick={()=>setSpecialistInput(specialistInput?.input_id===input.input_id?null:input)}>专用文字与车牌识别</button>}</div></li>)}</ul>{!page.items.length&&<p className="result-note">暂无已保存输入。</p>}<div className="context-heading"><button type="button" className="text-button" disabled={busy||listLoading||cursors.length===1} onClick={()=>setCursors(values=>values.slice(0,-1))}>上一页</button><button type="button" className="text-button" disabled={busy||listLoading||!page.has_more} onClick={()=>setCursors(values=>[...values,page.next_cursor])}>下一页</button></div></>}
    {specialistsEnabled&&specialistInput&&<SpecialistPanel key={specialistInput.input_id} input={specialistInput} onUnauthenticated={onUnauthenticated} onNavigateSource={onNavigateSource}/>}
  </section>
}
