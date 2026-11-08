import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React,{useEffect,useId,useRef,useState} from 'react'
import {formatTime} from './result-format.js'
import {checkedMediaBytes,mediaJson,mediaPhases,mediaFailure,validateMediaJob,validateMediaPage,validateMediaManifest} from './media-job-client.js'

export function useMediaFeature(onUnauthenticated){
  const [feature,setFeature]=useState({enabled:null,error:''}),[revision,setRevision]=useState(0)
  useEffect(()=>{
    const abort=new AbortController();setFeature({enabled:null,error:''})
    mediaJson('features',{signal:abort.signal}).then(value=>{if(!abort.signal.aborted)setFeature({enabled:value.media_jobs===true,error:''})})
      .catch(error=>{if(!abort.signal.aborted){setFeature({enabled:null,error:chineseMessage(error.message)});if(error.unauthorized)onUnauthenticated()}})
    return()=>abort.abort()
  },[revision])
  return {...feature,retry:()=>setRevision(n=>n+1)}
}

function MediaPreview({job,onUnauthenticated}){
  const [manifest,setManifest]=useState(null),[selected,setSelected]=useState(0),[image,setImage]=useState(null),[error,setError]=useState(''),[reload,setReload]=useState(0),[videoError,setVideoError]=useState(false)
  const video=useRef(null)
  useEffect(()=>{
    const abort=new AbortController();setManifest(null);setError('');setSelected(0);setVideoError(false)
    checkedMediaBytes(`media-clips/${job.job_id}/manifest`,job.manifest_sha256,65536,abort.signal).then(raw=>{
      const value=validateMediaManifest(JSON.parse(new TextDecoder().decode(raw)),job);if(!abort.signal.aborted)setManifest(value)
    }).catch(reason=>{if(!abort.signal.aborted){setError(chineseMessage(reason.message));if(reason.unauthorized)onUnauthenticated()}})
    return()=>abort.abort()
  },[job.job_id,job.manifest_sha256,reload])
  useEffect(()=>{
    const abort=new AbortController();let url;setImage(null)
    if(manifest){const sample=manifest.samples[selected];checkedMediaBytes(`media-clips/${job.job_id}/samples/${sample.index}`,sample.sha256,8*1024**2,abort.signal)
      .then(raw=>{if(raw.length!==sample.bytes)throw new Error('样本长度不一致，请重新读取。');if(!abort.signal.aborted){url=URL.createObjectURL(new Blob([raw],{type:'image/jpeg'}));setImage(url)}})
      .catch(reason=>{if(!abort.signal.aborted){setError(chineseMessage(reason.message));if(reason.unauthorized)onUnauthenticated()}})}
    return()=>{abort.abort();if(url)URL.revokeObjectURL(url)}
  },[manifest,selected])
  return <section className="media-preview" aria-label="已完成的分析片段">
    {!manifest&&!error&&<p role="status">正在校验片段清单…</p>}
    {error&&<FloatingNotice className="notice error" role="alert">{error}<button type="button" className="text-button" onClick={()=>{setImage(null);setReload(n=>n+1)}}>重新读取片段</button></FloatingNotice>}
    {manifest&&<><p className="result-note">分析片段 · 10 帧/秒 · {manifest.samples.length} 张逐秒样本。画面经过压缩，请结合线索详情中的原始关键帧核验。</p>
      <video key={reload} ref={video} controls preload="metadata" playsInline src={`/api/v1/media-clips/${job.job_id}/video`} onError={()=>setVideoError(true)} aria-label="播放分析片段"/>
      {videoError&&<FloatingNotice className="notice error" role="alert">视频暂时无法播放。<button type="button" className="text-button" onClick={()=>setReload(n=>n+1)}>重载视频</button></FloatingNotice>}
      <div className="context-frames" role="group" aria-label="选择逐秒样本">{manifest.samples.map(sample=><button type="button" className="admin-button secondary" key={sample.index} aria-pressed={selected===sample.index} onClick={()=>{setError('');setSelected(sample.index);if(video.current?.readyState>=1)video.current.currentTime=sample.offset_ms/1000}}>{(sample.offset_ms/1000).toFixed(1)} 秒</button>)}</div>
      {image?<img className="media-sample" src={image} alt={`片段内 ${(manifest.samples[selected].offset_ms/1000).toFixed(1)} 秒的分析样本`} onError={()=>setError('浏览器无法显示所选样本。')}/>:!error&&<p role="status">正在校验样本画面…</p>}
    </>}
  </section>
}

export function MediaJobList({camera,frameId=null,refresh=0,onUnauthenticated,header=null}){
  const [page,setPage]=useState(null),[cursors,setCursors]=useState([null]),[error,setError]=useState(''),[loading,setLoading]=useState(false),[reload,setReload]=useState(0),[busy,setBusy]=useState(false),[selected,setSelected]=useState(null),[message,setMessage]=useState('')
  const actions=useRef(null),requests=useRef(new Map()),after=cursors.at(-1)
  useEffect(()=>{actions.current=new AbortController();return()=>{actions.current.abort();actions.current=null}},[])
  useEffect(()=>{
    const abort=new AbortController();setLoading(true);setPage(null);setError('')
    const params=new URLSearchParams({limit:'10',...(frameId?{}:{camera_id:camera}),...(after?{after}:{})})
    mediaJson((frameId?`frames/${frameId}/media-jobs`:'media-jobs')+'?'+params,{signal:abort.signal}).then(value=>{
      const checked=validateMediaPage(value,camera,after);if(!abort.signal.aborted)setPage(checked)
    }).catch(reason=>{if(!abort.signal.aborted){setError(chineseMessage(reason.message));if(reason.unauthorized)onUnauthenticated()}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[camera,frameId,after,refresh,reload])
  const pending=page?.items.some(job=>['queued','active'].includes(job.state))
  useEffect(()=>{if(!pending||busy)return;const timer=setInterval(()=>{if(!document.hidden)setReload(n=>n+1)},10000);return()=>clearInterval(timer)},[pending,busy])
  async function retry(job){
    if(busy||!actions.current)return;const abort=actions.current;setBusy(true);setError('');setMessage('')
    const key=job.job_id+':'+job.revision;if(!requests.current.has(key))requests.current.set(key,crypto.randomUUID())
    try{
      const reply=await mediaJson(`media-jobs/${job.job_id}/retry`,{method:'POST',signal:abort.signal,body:JSON.stringify({request_id:requests.current.get(key),revision:job.revision})})
      validateMediaJob(reply,camera);if(reply.job_id!==job.job_id)throw new Error('返回的任务编号不一致，请刷新。')
      if(!abort.signal.aborted){setMessage(reply.dispatch_state==='paused'?'已重新排队，等待恢复处理。':'已重新排队。');setReload(n=>n+1)}
    }catch(reason){if(!abort.signal.aborted){setError(chineseMessage(reason.message));if(reason.unauthorized)onUnauthenticated()}}finally{if(!abort.signal.aborted)setBusy(false)}
  }
  return <section className="media-jobs-list" aria-label="录像制作任务">
    <div className="context-heading">{header||<h3>制作任务</h3>}<button type="button" className="text-button" disabled={loading||busy} onClick={()=>setReload(n=>n+1)}>刷新任务</button></div>
    {loading&&<p role="status">正在读取制作任务…</p>}{error&&<FloatingNotice className="notice error" role="alert">{error}<button type="button" className="text-button" disabled={busy} onClick={()=>setReload(n=>n+1)}>重新读取任务</button></FloatingNotice>}{message&&<FloatingNotice className="admin-success" role="status">{message}</FloatingNotice>}
    {page&&<><ul className="media-job-items">{page.items.map(job=><li key={job.job_id}><div><strong>{mediaPhases[job.dispatch_state]}</strong><p className="admin-muted">{job.input_kind==='session'?'实时留存片段':'历史录像片段'} · {((job.end_ms-job.start_ms)/1000).toFixed(1)} 秒 · {job.job_id.slice(0,8)}</p><p className="admin-muted">{formatTime(job.updated_at)} · 失败 {job.attempts} 次</p></div><div className="admin-row-actions">{job.last_error_code&&<InfoBubble title="查看警告说明" className="error">{mediaFailure[job.last_error_code]||'制作异常，请联系管理员。'}</InfoBubble>}{job.state==='failed'&&<button type="button" className="admin-button secondary" disabled={busy} onClick={()=>retry(job)}>重新排队</button>}{job.state==='completed'&&<button type="button" className="admin-button secondary" onClick={()=>setSelected(selected?.job_id===job.job_id?null:job)}>{selected?.job_id===job.job_id?'收起片段':'查看片段与样本'}</button>}</div></li>)}</ul>{!page.items.length&&<p className="admin-empty">暂无制作任务。</p>}
      <div className="admin-list-paging"><button type="button" className="admin-button secondary" disabled={busy||loading||cursors.length===1} onClick={()=>setCursors(values=>values.slice(0,-1))}>上一页</button><span>第 {cursors.length} 页</span><button type="button" className="admin-button secondary" disabled={busy||loading||!page.has_more} onClick={()=>setCursors(values=>[...values,page.next_cursor])}>下一页</button></div></>}
    {selected&&<><button type="button" className="text-button" onClick={()=>setSelected(null)}>收起片段预览</button><MediaPreview key={selected.job_id} job={selected} onUnauthenticated={onUnauthenticated}/></>}
  </section>
}

export default function MediaJobsPanel({cameras,refresh,onError}){
  const feature=useMediaFeature(()=>onError({unauthorized:true})),[choice,setChoice]=useState('')
  const cameraSelect=useId()
  const camera=cameras.some(value=>value.camera_id===choice)?choice:cameras[0]?.camera_id
  return <><div className="admin-section-title"><div><h1>录像制作</h1><p>查看片段制作进度、失败原因与结果。</p></div></div>
    {feature.error&&<FloatingNotice className="notice error" role="alert">{feature.error}<button type="button" className="text-button" onClick={feature.retry}>重新读取功能状态</button></FloatingNotice>}
    {feature.enabled===null&&!feature.error&&<p role="status">正在读取功能状态…</p>}
    {feature.enabled===false&&<p className="admin-empty">录像制作尚未启用。</p>}
    {feature.enabled&&<section className="admin-card"><div className="media-camera-filter"><label htmlFor={cameraSelect}>监控点位</label><select id={cameraSelect} value={camera||''} onChange={event=>setChoice(event.target.value)}>{cameras.map(value=><option key={value.camera_id} value={value.camera_id}>{value.display_name}</option>)}</select></div>
      {camera?<MediaJobList key={camera} camera={camera} refresh={refresh} onUnauthenticated={()=>onError({unauthorized:true})}/>:<p className="admin-empty">暂无可查看的点位。</p>}
    </section>}
  </>
}
