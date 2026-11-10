import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {businessApi} from './investigation-client.js'
import {exportApi,exportCommand,exportError,exportSize,exportStateLabel,transferLabel,validateExportInfo,validateExportPage,validateTransferPage,validateExportModule} from './export-client.js'
import {formatTime} from './result-format.js'

function Paging({page,data,busy,onPrevious,onNext,label}){
  return <nav className="admin-list-paging" aria-label={label}>
    <button type="button" className="text-button" disabled={busy||page===0} onClick={onPrevious}>上一页</button>
    <span>第 {page+1} 页</span>
    <button type="button" className="text-button" disabled={busy||!data?.has_more} onClick={onNext}>下一页</button>
  </nav>
}

function TransferHistory({jobId,epoch,onFailure}){
  const [data,setData]=useState(null),[error,setError]=useState(''),[loading,setLoading]=useState(false),[page,setPage]=useState(0),[cursors,setCursors]=useState([null]),[refresh,setRefresh]=useState(0)
  const callback=useRef(onFailure),records=useRef(null),followUntil=useRef(Date.now()+45000),lastEpoch=useRef(epoch),inFlight=useRef(false);callback.current=onFailure
  useEffect(()=>{if(lastEpoch.current===epoch)return;lastEpoch.current=epoch;followUntil.current=Date.now()+45000;setPage(0);setCursors([null]);setRefresh(value=>value+1)},[epoch])
  const cursor=cursors[page]
  useEffect(()=>{
    const abort=new AbortController();inFlight.current=true;setLoading(true);setError('')
    const params=new URLSearchParams({limit:'10',...(cursor?{after:cursor}:{})})
    exportApi(`/api/v1/exports/${jobId}/transfers?${params}`,{signal:abort.signal}).then(validateTransferPage).then(value=>{if(!abort.signal.aborted){setData(value);records.current=value}}).catch(failure=>{if(!abort.signal.aborted){setError(chineseMessage(failure.message));callback.current(failure)}}).finally(()=>{if(!abort.signal.aborted){inFlight.current=false;setLoading(false)}})
    return()=>{inFlight.current=false;abort.abort()}
  },[jobId,cursor,refresh])
  useEffect(()=>{
    const timer=setInterval(()=>{if(!document.hidden&&!inFlight.current&&(Date.now()<followUntil.current||records.current?.items.some(record=>record.state==='streaming'&&!record.stale)))setRefresh(value=>value+1)},7000)
    return()=>clearInterval(timer)
  },[])
  function move(next){setData(null);setPage(next)}
  return <section className="export-transfers" aria-label="下载记录" aria-busy={loading}>
    <div className="admin-card-heading"><h4 className="help-heading">下载记录<InfoBubble title="下载记录说明"><p className="admin-muted">记录服务端发送的字节；发送完成不代表浏览器已保存文件。断线或服务重启可能使结束状态未确认。</p></InfoBubble></h4><button type="button" className="text-button" disabled={loading} onClick={()=>setRefresh(value=>value+1)}>刷新记录</button></div>
    
    {error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}
    {!data&&loading&&<p role="status">正在读取下载记录…</p>}
    {data&&<><ul className="export-transfer-list">{data.items.map(record=><li key={record.transfer_id}>
      <strong>{transferLabel(record)}</strong><span>{formatTime(record.started_at)}</span>
      <p>服务端已发送 {exportSize(record.sent_bytes)} / 本次范围 {exportSize(record.expected_bytes)} · 字节 {record.range_start}–{record.range_end}</p>
      <details><summary>传输编号与包摘要</summary><code>{record.transfer_id}</code><code className="export-sha">{record.artifact_sha256}</code></details>
    </li>)}</ul>{!data.items.length&&<p className="admin-muted">尚无下载记录。发起下载后可刷新查看。</p>}</>}
    <Paging page={page} data={data} busy={loading} label="下载记录分页" onPrevious={()=>move(page-1)} onNext={()=>{setCursors(value=>[...value.slice(0,page+1),data.next_cursor]);move(page+1)}}/>
  </section>
}

function ExportWorkspace({collection,user,onUnauthenticated,module,refreshModule,readingModule}){
  const [info,setInfo]=useState(null),[part,setPart]=useState(1),[nativeVideo,setNativeVideo]=useState(true),[normalizedVideo,setNormalizedVideo]=useState(true)
  const [data,setData]=useState(null),[loading,setLoading]=useState(false),[listError,setListError]=useState(''),[error,setError]=useState(''),[message,setMessage]=useState('')
  const [busy,setBusy]=useState(false),[reason,setReason]=useState(''),[page,setPage]=useState(0),[cursors,setCursors]=useState([null]),[refresh,setRefresh]=useState(0),[history,setHistory]=useState(null),[downloadEpoch,setDownloadEpoch]=useState(0)
  const action=useRef(null),pending=useRef(false),inFlight=useRef(false),callbacks=useRef(onUnauthenticated);callbacks.current=onUnauthenticated
  useEffect(()=>{action.current=new AbortController();return()=>action.current.abort()},[])
  function failure(problem){if(problem.unauthorized)callbacks.current()}
  const cursor=cursors[page]
  useEffect(()=>{
    const abort=new AbortController();inFlight.current=true;setLoading(true);setListError('')
    const params=new URLSearchParams({collection_id:collection.collection_id,limit:'10',...(cursor?{after:cursor}:{})})
    exportApi(`/api/v1/exports?${params}`,{signal:abort.signal}).then(value=>validateExportPage(value,collection.collection_id)).then(value=>{if(!abort.signal.aborted)setData(value)}).catch(problem=>{if(!abort.signal.aborted){setListError(chineseMessage(problem.message));if(problem.unauthorized)callbacks.current()}}).finally(()=>{if(!abort.signal.aborted){inFlight.current=false;setLoading(false)}})
    return()=>{inFlight.current=false;abort.abort()}
  },[collection.collection_id,cursor,refresh])
  const needsPolling=data?.items.some(job=>['queued','active'].includes(job.state)||(job.state==='cancelled'&&job.artifact_cleanup_pending))
  useEffect(()=>{
    if(!needsPolling)return
    const timer=setInterval(()=>{if(!document.hidden&&!pending.current&&!inFlight.current)setRefresh(value=>value+1)},7000)
    return()=>clearInterval(timer)
  },[needsPolling])
  async function execute(operation){
    if(pending.current)return
    pending.current=true;setBusy(true);setError('');setMessage('')
    try{await operation()}catch(problem){if(!action.current.signal.aborted){setError(chineseMessage(problem.message));failure(problem)}}finally{pending.current=false;if(!action.current.signal.aborted)setBusy(false)}
  }
  function prepare(){return execute(async()=>{
    const value=validateExportInfo(await exportApi(`/api/v1/exports/info?collection_id=${collection.collection_id}&revision=${collection.revision}`,{signal:action.current.signal}),collection)
    if(!action.current.signal.aborted){setInfo(value);setPart(1)}
  })}
  function create(event){event.preventDefault();if(!module?.can_create)return;return execute(async()=>{
    await exportCommand(user,'/api/v1/exports',{collection_id:collection.collection_id,collection_revision:collection.revision,part,native_video:nativeVideo,normalized_video:normalizedVideo},collection.collection_id,action.current.signal)
    if(!action.current.signal.aborted){setPage(0);setCursors([null]);setRefresh(value=>value+1);setMessage('已提交制作任务，可在下方查看进度。')}
  })}
  function control(job,command){if(command==='retry'&&!module?.can_create)return;return execute(async()=>{
    await exportCommand(user,`/api/v1/exports/${job.export_id}/control`,{expected_revision:job.revision,action:command,reason:reason.trim()},collection.collection_id,action.current.signal)
    if(!action.current.signal.aborted){setRefresh(value=>value+1);setMessage(command==='retry'?'已重新排队。':'已撤销，制品将由后台回收；原图和原录像保留。')}
  })}
  function move(next){setData(null);setHistory(null);setPage(next)}
  function startDownload(job){setHistory(job.export_id);setDownloadEpoch(value=>value+1);setMessage('已发起浏览器下载，请在浏览器下载列表查看保存状态。')}
  return <section className="admin-card video-evidence-panel">
    <div className="admin-card-heading"><div><h2 className="help-heading">视频证据包<InfoBubble title="视频证据包说明"><p className="admin-muted">新卷最多 2 张独立关键帧，附同段录像、线索复核记录和文件摘要。缺少的附件会列在包内清单中。</p><p>包含已留存片段；整段历史原文件不在本包范围。</p></InfoBubble></h2></div><button type="button" className="admin-button secondary" disabled={busy} onClick={prepare}>{busy?'正在处理…':'准备视频分卷'}</button></div>
    <div className="admin-card-heading"><span className="admin-badge" role="status">{!module?'模块状态待确认':!module.installed?'尚未安装':!module.enabled?'制作已关闭':module.online?'制作服务在线':'制作服务暂未在线'}</span><div className="help-heading"><button type="button" className="text-button" disabled={readingModule} onClick={refreshModule}>{readingModule?'正在刷新…':'刷新模块状态'}</button><InfoBubble title="模块开关与既有任务">管理员可在模块配置中启用制作。关闭期间，新建和重试暂停，在途任务可完成；已有包仍可下载或撤销。制作服务有独立空间配额，完成包按保留策略过期回收。</InfoBubble></div></div>
    {info&&<form className="export-form" onSubmit={create}>
      <label>制作分卷<select value={part} disabled={busy} onChange={event=>setPart(Number(event.target.value))}>{Array.from({length:info.parts},(_,index)=><option key={index+1} value={index+1}>第 {index+1} / {info.parts} 卷</option>)}</select></label>
      <fieldset disabled={busy}><legend>随包录像</legend><label className="admin-check"><input type="checkbox" checked={nativeVideo} onChange={event=>setNativeVideo(event.target.checked)}/>原码留存段</label><label className="admin-check"><input type="checkbox" checked={normalizedVideo} onChange={event=>setNormalizedVideo(event.target.checked)}/>标准化视频 片段</label></fieldset>
      <span className="admin-muted">共 {info.frame_count} 张独立原图</span>
      <button type="submit" className="admin-button" disabled={busy||!module?.can_create||!info.frame_count||(!nativeVideo&&!normalizedVideo)}>提交制作</button>
    </form>}
    {error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{message&&<FloatingNotice className="admin-success" role="status">{message}</FloatingNotice>}
    <div className="admin-card-heading export-list-heading"><h3>我的制作任务</h3><button type="button" className="text-button" disabled={loading||busy} onClick={()=>setRefresh(value=>value+1)}>刷新任务</button></div>
    <label className="export-reason">重试或撤销原因<input value={reason} disabled={busy} onChange={event=>setReason(event.target.value)} maxLength={300} placeholder="操作前填写原因，将保留审计记录"/></label>
    {listError&&<FloatingNotice className="notice error" role="alert">{listError}</FloatingNotice>}
    {!data&&loading&&<p role="status">正在读取制作任务…</p>}
    <div aria-busy={loading}><ul className="export-job-list">{data?.items.map(job=>{
      const current=job.collection_revision===collection.revision
      return <li key={job.export_id}>
        <div className="admin-card-heading"><div><strong>第 {job.part} / {job.parts} 卷 · 清单版本 {job.collection_revision}</strong><p className="admin-muted">{formatTime(job.created_at)} · {job.asset_count} 个附件（录像 {job.video_count} 个） · {exportSize(job.artifact_bytes)}</p></div><span className="admin-badge">{job.state==='cancelled'?(job.artifact_cleanup_pending?`${exportStateLabel(job)}，等待回收`:`${exportStateLabel(job)}，制品已回收`):exportStateLabel(job)}</span></div>
        
        
        
        {job.artifact_sha256&&<details><summary>包摘要与任务编号</summary><p>校验摘要 <code className="export-sha">{job.artifact_sha256}</code></p><code>{job.export_id}</code></details>}
        <div className="admin-row-actions export-actions">{(!current||job.missing_attachment_count>0||job.last_error_code)&&<InfoBubble title="证据包状态说明" className={job.last_error_code?'error':''}>{!current&&<p className="result-note">对应旧版清单，请按当前版本重新制作。</p>}{job.missing_attachment_count>0&&<p className="result-note">缺少 {job.missing_attachment_count} 项录像附件，原因已记录在包内清单文件。</p>}{job.last_error_code&&<p>{exportError(job.last_error_code)}{job.state==='queued'?' 系统将有限次重试。':''}</p>}</InfoBubble>}
          {job.state==='completed'&&current&&<a className="admin-button secondary" href={`/api/v1/exports/${job.export_id}/download`} download target="_blank" rel="noopener" onClick={()=>startDownload(job)}>下载证据包</a>}
          {job.state==='failed'&&current&&<button type="button" className="text-button" disabled={busy||!module?.can_create||!reason.trim()} onClick={()=>control(job,'retry')}>重新制作</button>}
          {job.state!=='cancelled'&&<button type="button" className="text-button" disabled={busy||!reason.trim()} onClick={()=>control(job,'cancel')}>{job.state==='completed'?'撤销并回收':'取消任务'}</button>}
          <button type="button" className="text-button" aria-expanded={history===job.export_id} onClick={()=>setHistory(value=>value===job.export_id?null:job.export_id)}>{history===job.export_id?'收起下载记录':'查看下载记录'}</button>
        </div>
        {history===job.export_id&&<TransferHistory key={job.export_id} jobId={job.export_id} epoch={downloadEpoch} onFailure={failure}/>}
      </li>
    })}</ul>{data&&!data.items.length&&<p className="admin-empty">尚无视频证据包任务，准备分卷后即可提交制作。</p>}</div>
    <Paging page={page} data={data} busy={loading||busy} label="证据包任务分页" onPrevious={()=>move(page-1)} onNext={()=>{setCursors(value=>[...value.slice(0,page+1),data.next_cursor]);move(page+1)}}/>
  </section>
}

export default function VideoEvidencePanel({collection,onUnauthenticated}){
  const [user,setUser]=useState(null),[error,setError]=useState(''),[refresh,setRefresh]=useState(0),[module,setModule]=useState(null),[readingModule,setReadingModule]=useState(false)
  const callback=useRef(onUnauthenticated),active=useRef(null),polling=useRef(false);callback.current=onUnauthenticated
  async function refreshModule(){
    const signal=active.current?.signal;if(!signal||signal.aborted||polling.current)return
    polling.current=true;setReadingModule(true)
    try{
      const value=validateExportModule(await exportApi('/api/v1/exports/status',{signal}))
      if(!signal.aborted){setModule(value);setError('')}
    }catch(problem){if(!signal.aborted){setModule(null);setError(chineseMessage(problem.message));if(problem.unauthorized)callback.current()}}
    finally{if(!signal.aborted){polling.current=false;setReadingModule(false)}}
  }
  useEffect(()=>{
    const abort=new AbortController();active.current=abort;polling.current=false;setError('');setUser(null);setModule(null);setReadingModule(false)
    async function connect(){
      const flags=await businessApi('/api/v1/features',{signal:abort.signal});if(flags.exports!==true)return
      const session=await businessApi('/api/session',{signal:abort.signal})
      if(session.authenticated!==true)throw Object.assign(new Error('请重新登录。'),{unauthorized:true})
      if(typeof session.username!=='string'||!session.username||session.username.length>100)throw new Error('当前账号信息不完整，请重新登录。')
      if(!abort.signal.aborted){setUser(session.username);await refreshModule()}
    }
    connect().catch(problem=>{if(!abort.signal.aborted){setError(chineseMessage(problem.message));if(problem.unauthorized)callback.current()}})
    return()=>abort.abort()
  },[refresh])
  useEffect(()=>{
    if(!user)return
    const timer=setInterval(()=>{if(!document.hidden)refreshModule()},15000)
    return()=>clearInterval(timer)
  },[user])
  return <>{error&&<FloatingNotice className="notice error" role="alert">{error}<button type="button" className="text-button" onClick={()=>setRefresh(value=>value+1)}>重新连接视频证据包</button></FloatingNotice>}
    {user&&<ExportWorkspace key={`${user}:${collection.collection_id}:${collection.revision}`} collection={collection} user={user} module={module} refreshModule={refreshModule} readingModule={readingModule} onUnauthenticated={()=>callback.current()}/>}</>
}
