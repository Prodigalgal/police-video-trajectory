import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {businessApi} from './investigation-client.js'
import VideoEvidencePanel from './VideoEvidencePanel.jsx'
import {formatTime} from './result-format.js'
import {imagePackageContext,imagePackagePreviewPath,imagePackageDownloadPath,validateImagePackagePreview,imagePackageSize,packageIntegrityNames,packageReviewLabel} from './image-package-client.js'

export default function EvidencePackagePanel({collection,onUnauthenticated}){
  return <><ImageEvidencePackagePanel collection={collection} onUnauthenticated={onUnauthenticated}/><VideoEvidencePanel collection={collection} onUnauthenticated={onUnauthenticated}/></>
}

function ImageEvidencePackagePanel({collection,onUnauthenticated}){
  const [enabled,setEnabled]=useState(false),[parts,setParts]=useState(null),[part,setPart]=useState(1),[preview,setPreview]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState('')
  const action=useRef(null),pending=useRef(false),callbacks=useRef(onUnauthenticated),downloads=useRef(new Map());callbacks.current=onUnauthenticated
  useEffect(()=>{action.current=new AbortController();return()=>{action.current.abort();for(const [url,timer] of downloads.current){clearTimeout(timer);URL.revokeObjectURL(url)}downloads.current.clear()}},[])
  useEffect(()=>{const abort=new AbortController();setParts(null);setPart(1);setPreview(null);setMessage('');businessApi('/api/v1/features',{signal:abort.signal}).then(value=>{if(!abort.signal.aborted)setEnabled(value.investigation===true)}).catch(failure=>{if(!abort.signal.aborted&&failure.unauthorized)callbacks.current()});return()=>abort.abort()},[collection.collection_id,collection.revision])
  const context=imagePackageContext(collection,part),current=preview?.key===context.key?preview.value:null
  async function prepare(){
    if(pending.current)return
    pending.current=true;setBusy(true);setError('');setPreview(null);setMessage('')
    try{
      const value=validateImagePackagePreview(await businessApi(imagePackagePreviewPath(context),{signal:action.current.signal}),context)
      if(!action.current.signal.aborted){setParts(value.parts);setPreview({key:context.key,value})}
    }catch(failure){if(!action.current.signal.aborted){setError(chineseMessage(failure.message));if([400,409].includes(failure.status)){setParts(null);setPart(1)}if(failure.unauthorized)callbacks.current()}}
    finally{pending.current=false;if(!action.current.signal.aborted)setBusy(false)}
  }
  async function download(){
    if(pending.current||!current)return
    pending.current=true;setBusy(true);setError('');setMessage('')
    try{
      const response=await fetch(imagePackageDownloadPath(current,context),{signal:action.current.signal,cache:'no-store'})
      if(response.status===401){callbacks.current();return}
      if(!response.ok){const value=await response.json();if(response.status===409)setPreview(null);throw new Error(chineseMessage(value.detail,'证据包暂不可用，请重新核验本卷。'))}
      if(!response.headers.get('content-type')?.includes('application/zip'))throw new Error('证据包格式不正确。')
      const reader=response.body.getReader(),chunks=[];let total=0
      try{for(;;){const {value,done}=await reader.read();if(done)break;total+=value.length;if(total>67*1024*1024){await reader.cancel();throw new Error('证据包超过容量限制。')}chunks.push(value)}}finally{reader.releaseLock()}
      const blob=new Blob(chunks,{type:'application/zip'}),buffer=await blob.arrayBuffer(),digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',buffer))).map(value=>value.toString(16).padStart(2,'0')).join('')
      if(digest!==response.headers.get('x-content-sha256'))throw new Error('证据包摘要不一致，已停止下载。')
      if(action.current.signal.aborted)return
      const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=`图片证据-${collection.collection_id}-修订${collection.revision}-第${part}卷.zip`;link.click();downloads.current.set(url,setTimeout(()=>{URL.revokeObjectURL(url);downloads.current.delete(url)},30000));setMessage(`第 ${part} 卷已核验，已发起浏览器下载。`)
    }catch(failure){if(!action.current.signal.aborted)setError(chineseMessage(failure.message))}finally{pending.current=false;if(!action.current.signal.aborted)setBusy(false)}
  }
  if(!enabled)return null
  return <section className="admin-card image-package-panel" aria-busy={busy}><div className="admin-card-heading"><div><h2 className="help-heading">图片证据包<InfoBubble title="图片证据包说明"><p>每卷最多 16 张去重原图，保留完整清单和全部业务复核标记，包含已排除记录；不会把复核标记当成身份结论。</p><p>核验不保存大制品。下载重新核对预览来源；线索或复核变化后需要再次核验。录像仍通过已有片段入口查看。</p></InfoBubble></h2></div><button type="button" className="admin-button secondary" disabled={busy} onClick={prepare}>{busy?'正在处理…':parts?'核验本卷':'核验分卷'}</button></div>
    {parts&&<div className="admin-filters"><label>下载分卷<select aria-label="证据包分卷" value={part} disabled={busy} onChange={event=>{setPart(Number(event.target.value));setPreview(null);setError('');setMessage('')}}>{Array.from({length:parts},(_,index)=><option key={index+1} value={index+1}>第 {index+1} / {parts} 卷</option>)}</select></label><button type="button" className="admin-button" disabled={busy||!current?.download_allowed} onClick={download}>核验并下载</button>{current&&<span className="admin-muted">本卷 {current.frames.length} 张 · {imagePackageSize(current.image_bytes)} · 共 {current.frame_count} 张独立原图</span>}{current&&!current.download_allowed&&<InfoBubble title="本卷暂不能下载">{current.download_reason==='empty_package'?'当前清单暂无可导出的原图；完整业务记录仍可使用上方导出入口。':'本卷原图超过 64 兆字节，请拆分线索集后重新核验。'}</InfoBubble>}</div>}
    {current&&<section className="image-package-preview" aria-label="本卷内容"><ol>{current.frames.map((frame,index)=><li key={frame.frame_id}><div className="package-frame-heading"><strong>{(part-1)*16+index+1}. {frame.camera_name}</strong><span>{imagePackageSize(frame.bytes)}</span><a className="text-button" href={`/api/v1/frames/${frame.frame_id}/image`} target="_blank" rel="noreferrer">查看原图</a></div><div className="admin-muted">{frame.source_time&&frame.source_time_origin==='verified'?'来源时间 '+formatTime(frame.source_time):'记录时间 '+(formatTime(frame.received_at)||'未知')} · 关联 {frame.observations.length} 条线索</div><details><summary>关联线索与核验依据</summary><ul>{frame.observations.map(observation=><li key={observation.observation_id}><span>{observation.target_type==='person'?'人物':'车辆'} · {packageReviewLabel(observation)}</span><span className="admin-muted">记录版本 {observation.record_version}</span><code>{observation.observation_id}</code></li>)}</ul><p className="admin-muted">原图：{packageIntegrityNames[frame.capture_integrity]}</p><code>{frame.sha256}</code></details></li>)}</ol>{!current.frames.length&&<p className="admin-muted" role="status">本卷暂无原图。</p>}<details className="evidence-record"><summary>分卷来源摘要</summary><code>{current.source_sha256}</code><p className="admin-muted">核验于 {formatTime(current.prepared_at)}</p></details></section>}
    {error&&<FloatingNotice role="alert" className="notice error">{error}</FloatingNotice>}{message&&<FloatingNotice role="status" className="admin-success">{message}</FloatingNotice>}</section>
}
