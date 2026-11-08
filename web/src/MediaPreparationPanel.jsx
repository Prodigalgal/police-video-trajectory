import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {mediaJson,validateMediaJob} from './media-job-client.js'
import {MediaJobList,useMediaFeature} from './MediaJobsPanel.jsx'

export default function MediaPreparationPanel({detail,digest,onUnauthenticated}){
  const feature=useMediaFeature(onUnauthenticated),[busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState(''),[refresh,setRefresh]=useState(0)
  const action=useRef(null),request=useRef(null),video=detail.video
  const eligible=video?.kind==='session_segment'&&video.normalization_eligible===true&&/^[a-f0-9]{64}$/.test(video.recording_manifest_sha256||'')
  useEffect(()=>{action.current=new AbortController();return()=>{action.current.abort();action.current=null}},[])
  async function create(){
    if(busy||!eligible||digest!=='valid'||!action.current)return;const abort=action.current;setBusy(true);setError('');setMessage('')
    if(!request.current)request.current=crypto.randomUUID()
    try{
      const job=validateMediaJob(await mediaJson(`frames/${detail.frame_id}/media-jobs`,{method:'POST',signal:abort.signal,body:JSON.stringify({request_id:request.current,recording_manifest_sha256:video.recording_manifest_sha256})}),detail.camera_id)
      if(!abort.signal.aborted){setMessage(job.state==='completed'?'此片段已制作，可在下方查看。':job.dispatch_state==='paused'?'制作任务已登记，等待恢复处理。':'制作任务已登记，可在下方查看进度。');setRefresh(n=>n+1)}
    }catch(reason){if(!abort.signal.aborted){setError(chineseMessage(reason.message));if(reason.unauthorized)onUnauthenticated()}}finally{if(!abort.signal.aborted)setBusy(false)}
  }
  if(feature.error)return <FloatingNotice className="notice error" role="alert">{feature.error}<button type="button" className="text-button" onClick={feature.retry}>重读片段功能状态</button></FloatingNotice>
  if(!feature.enabled)return null
  return <section className="evidence-context" aria-label="准备分析片段"><div className="context-heading"><h3>分析片段</h3><button type="button" className="admin-button secondary" disabled={busy||!eligible||digest!=='valid'} onClick={create}>{busy?'正在提交…':'准备分析片段'}</button></div>
    <p className="result-note">将本帧所在留存段制作成短视频和逐秒画面，供后续分析使用。</p>
    {!eligible&&<p className="result-note">当前帧没有可制作的完整留存段。</p>}{eligible&&digest!=='valid'&&<p className="result-note">原图摘要核验通过后可提交。</p>}
    {error&&<FloatingNotice className="notice error" role="alert">{error} 可再次提交以查询同一次请求的结果。</FloatingNotice>}{message&&<FloatingNotice className="admin-success" role="status">{message}</FloatingNotice>}
    {eligible&&<MediaJobList key={detail.frame_id} camera={detail.camera_id} frameId={detail.frame_id} refresh={refresh} onUnauthenticated={onUnauthenticated}/>}
  </section>
}
