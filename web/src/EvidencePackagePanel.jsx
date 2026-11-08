import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {businessApi} from './investigation-client.js'
import VideoEvidencePanel from './VideoEvidencePanel.jsx'

export default function EvidencePackagePanel({collection,onUnauthenticated}){
  return <><ImageEvidencePackagePanel collection={collection} onUnauthenticated={onUnauthenticated}/><VideoEvidencePanel collection={collection} onUnauthenticated={onUnauthenticated}/></>
}

function ImageEvidencePackagePanel({collection,onUnauthenticated}){
  const [enabled,setEnabled]=useState(false),[parts,setParts]=useState(null),[part,setPart]=useState(1),[busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState('')
  const action=useRef(null),callbacks=useRef(onUnauthenticated),downloads=useRef(new Map());callbacks.current=onUnauthenticated
  useEffect(()=>{action.current=new AbortController();return()=>{action.current.abort();for(const [url,timer] of downloads.current){clearTimeout(timer);URL.revokeObjectURL(url)}downloads.current.clear()}},[])
  useEffect(()=>{const abort=new AbortController();setParts(null);setPart(1);setMessage('');businessApi('/api/v1/features',{signal:abort.signal}).then(value=>{if(!abort.signal.aborted)setEnabled(value.investigation===true)}).catch(failure=>{if(!abort.signal.aborted&&failure.unauthorized)callbacks.current()});return()=>abort.abort()},[collection.collection_id,collection.revision])
  async function prepare(){
    setBusy(true);setError('')
    try{const value=await businessApi(`/api/v1/collections/${collection.collection_id}/package-info?revision=${collection.revision}`,{signal:action.current.signal});if(!Number.isSafeInteger(value.parts)||value.parts<1||value.parts>13)throw new Error('证据包分卷记录无效。');setParts(value)}catch(failure){if(!action.current.signal.aborted){setError(chineseMessage(failure.message));if(failure.unauthorized)callbacks.current()}}finally{if(!action.current.signal.aborted)setBusy(false)}
  }
  async function download(){
    setBusy(true);setError('');setMessage('')
    try{
      const response=await fetch(`/api/v1/collections/${collection.collection_id}/package?revision=${collection.revision}&part=${part}`,{signal:action.current.signal,cache:'no-store'})
      if(response.status===401){callbacks.current();return}
      if(!response.ok){const value=await response.json();throw new Error('证据包暂不可用，请重新准备。')}
      if(!response.headers.get('content-type')?.includes('application/zip'))throw new Error('证据包格式不正确。')
      const reader=response.body.getReader(),chunks=[];let total=0
      try{for(;;){const {value,done}=await reader.read();if(done)break;total+=value.length;if(total>67*1024*1024){await reader.cancel();throw new Error('证据包超过容量限制。')}chunks.push(value)}}finally{reader.releaseLock()}
      const blob=new Blob(chunks,{type:'application/zip'}),buffer=await blob.arrayBuffer(),digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',buffer))).map(value=>value.toString(16).padStart(2,'0')).join('')
      if(digest!==response.headers.get('x-content-sha256'))throw new Error('证据包摘要不一致，已停止下载。')
      if(action.current.signal.aborted)return
      const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=`evidence-${collection.collection_id}-r${collection.revision}-part${part}.zip`;link.click();downloads.current.set(url,setTimeout(()=>{URL.revokeObjectURL(url);downloads.current.delete(url)},30000));setMessage(`第 ${part} 卷已核验，已发起浏览器下载。`)
    }catch(failure){if(!action.current.signal.aborted)setError(chineseMessage(failure.message))}finally{if(!action.current.signal.aborted)setBusy(false)}
  }
  if(!enabled)return null
  return <section className="admin-card"><div className="admin-card-heading"><div><h2 className="help-heading">图片证据包<InfoBubble title="图片证据包说明"><p className="admin-muted">包含原始关键帧、完整线索/复核清单和摘要；录像仍通过对应片段入口查看。</p></InfoBubble></h2></div><button type="button" className="admin-button secondary" disabled={busy} onClick={prepare}>{busy?'正在核验…':'准备分卷'}</button></div>{parts&&<div className="admin-filters"><label>下载分卷<select aria-label="证据包分卷" value={part} disabled={busy} onChange={event=>setPart(Number(event.target.value))}>{Array.from({length:parts.parts},(_,index)=><option key={index+1} value={index+1}>第 {index+1} / {parts.parts} 卷</option>)}</select></label><button type="button" className="admin-button" disabled={busy} onClick={download}>核验并下载</button><span className="admin-muted">共 {parts.frame_count} 张独立原图</span></div>}{error&&<FloatingNotice role="alert" className="notice error">{error}</FloatingNotice>}{message&&<FloatingNotice role="status" className="admin-success">{message}</FloatingNotice>}</section>
}
