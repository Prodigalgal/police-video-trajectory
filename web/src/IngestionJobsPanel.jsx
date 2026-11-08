import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {businessApi} from './investigation-client.js'
import {ingestionApi,ingestionCommand,ingestionErrors,mediaPosition,sourceStates,validateIngestionPage} from './ingestion-client.js'
import {formatTime} from './result-format.js'

function useIngestionPage(path,refresh,onError){
  const [result,setResult]=useState({loading:true,data:null,error:''}),report=useRef(onError);report.current=onError
  useEffect(()=>{
    const abort=new AbortController();setResult({loading:true,data:null,error:''})
    ingestionApi(path,{signal:abort.signal}).then(value=>{
      const data=validateIngestionPage(value,path.startsWith('/sources')?'source_id':'task_id')
      if(!abort.signal.aborted)setResult({loading:false,data,error:''})
    }).catch(error=>{if(!abort.signal.aborted){setResult({loading:false,data:null,error:chineseMessage(error.message)});if(error.unauthorized)report.current(error)}})
    return()=>abort.abort()
  },[path,refresh])
  return result
}

function Paging({cursors,setCursors,data,disabled}){
  return <div className="admin-list-paging"><button type="button" className="admin-button secondary" disabled={disabled||cursors.length===1} onClick={()=>setCursors(values=>values.slice(0,-1))}>上一页</button><span>第 {cursors.length} 页</span><button type="button" className="admin-button secondary" disabled={disabled||!data?.has_more} onClick={()=>setCursors(values=>[...values,data.next_cursor])}>下一页</button></div>
}

function SourceBatch({cameras,refresh,busy,run,onError,reason,onCreated}){
  const [cursors,setCursors]=useState([null]),[selected,setSelected]=useState({}),[title,setTitle]=useState(''),[start,setStart]=useState('0'),[end,setEnd]=useState(''),[priority,setPriority]=useState('0')
  const [registerCamera,setRegisterCamera]=useState(''),[kind,setKind]=useState('rtsp'),[error,setError]=useState('')
  const query=new URLSearchParams({limit:'50',...(cursors.at(-1)?{after:cursors.at(-1)}:{})})
  const page=useIngestionPage('/sources?'+query,refresh,onError)
  const sourceList=Object.values(selected),archiveSelected=sourceList.some(source=>source.source_type==='archive')
  const installed=cameras.filter(camera=>/^recording-0[1-4]$/.test(camera.camera_id))
  const camera=installed.some(value=>value.camera_id===registerCamera)?registerCamera:installed[0]?.camera_id||''
  async function register(event){
    event.preventDefault();setError('')
    try{await run('/sources',{camera_id:camera,source_type:kind});setRegisterCamera(camera)}catch(failure){setError(chineseMessage(failure.message))}
  }
  async function toggle(source){
    setError('')
    try{await run('/sources/'+source.source_id,{expected_revision:source.revision,enabled:!source.enabled,reason:reason.trim()},'PUT')}catch(failure){setError(chineseMessage(failure.message))}
  }
  async function create(event){
    event.preventDefault();setError('')
    const startSeconds=Number(start),endSeconds=end===''?null:Number(end)
    if(!sourceList.length||sourceList.length>12||!title.trim()||archiveSelected&&(!Number.isFinite(startSeconds)||startSeconds<0||startSeconds>604800||endSeconds!==null&&(!Number.isFinite(endSeconds)||endSeconds<=startSeconds||endSeconds>604800))){
      setError('请选择 1–12 个来源，并填写有效的批次名称和历史录像时间范围。');return
    }
    const items=sourceList.sort((left,right)=>left.source_id.localeCompare(right.source_id)).map(source=>({source_id:source.source_id,start_ms:source.source_type==='archive'?Math.round(startSeconds*1000):0,end_ms:source.source_type==='archive'&&endSeconds!==null?Math.round(endSeconds*1000):null,priority:Number(priority)}))
    try{const result=await run('/batches',{title:title.trim(),items});setSelected({});setTitle('');onCreated(result.batch_id)}catch(failure){setError(chineseMessage(failure.message))}
  }
  return <section className="admin-card" aria-label="来源与采集批次"><h2>来源与批次</h2><p className="admin-muted">新登记来源默认停用。创建批次只加入队列；领取还需要总开关、来源、点位和对应 处理节点 均可用。</p>
    <form className="ingestion-inline-form" onSubmit={register}><label>监控点位<select value={camera} disabled={busy} onChange={event=>setRegisterCamera(event.target.value)}>{installed.map(value=><option key={value.camera_id} value={value.camera_id}>{value.display_name}</option>)}</select></label><label>来源类型<select value={kind} disabled={busy} onChange={event=>setKind(event.target.value)}><option value="rtsp">实时视频流</option><option value="archive">历史录像</option></select></label><button className="admin-button secondary" type="submit" disabled={busy||!camera}>登记来源</button></form>
    {error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{page.error&&<FloatingNotice className="notice error" role="alert">{page.error}</FloatingNotice>}{page.loading&&<p role="status">正在读取来源…</p>}
    {page.data&&<><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>选择</th><th>点位与类型</th><th>来源状态</th><th>操作</th></tr></thead><tbody>{page.data.items.map(source=><tr key={source.source_id}><td><input type="checkbox" aria-label={`选择 ${source.display_name} ${source.source_type==='rtsp'?'实时视频流':'历史录像'}`} checked={Boolean(selected[source.source_id])} disabled={busy||!selected[source.source_id]&&sourceList.length>=12} onChange={event=>{const checked=event.target.checked;setSelected(values=>{const next={...values};if(checked)next[source.source_id]=source;else delete next[source.source_id];return next})}}/></td><td><strong>{source.display_name}</strong><small>{source.source_type==='rtsp'?'实时视频流':'历史录像'} · {source.source_id.slice(0,8)}</small></td><td>{source.enabled?'来源已启用':'来源已停用'}{!source.camera_enabled&&<small>点位已停用</small>}</td><td><button type="button" className="text-button" disabled={busy||!reason.trim()} onClick={()=>toggle(source)}>{source.enabled?'停用来源':'启用来源'}</button></td></tr>)}</tbody></table></div>{!page.data.items.length&&<p className="admin-empty">暂无可管理的来源，请先登记。</p>}<Paging cursors={cursors} setCursors={setCursors} data={page.data} disabled={busy||page.loading}/></>}
    <form onSubmit={create} className="ingestion-batch-form"><label>批次名称<input value={title} maxLength={160} required disabled={busy} onChange={event=>setTitle(event.target.value)}/></label><div className="ingestion-inline-form"><label>历史起点（秒）<input type="number" min="0" max="604800" step="0.001" value={start} disabled={busy||!archiveSelected} required={archiveSelected} onChange={event=>setStart(event.target.value)}/></label><label>历史终点（秒，可留空）<input type="number" min="0.001" max="604800" step="0.001" value={end} disabled={busy||!archiveSelected} onChange={event=>setEnd(event.target.value)}/></label><label>优先级<select value={priority} disabled={busy} onChange={event=>setPriority(event.target.value)}>{Array.from({length:10},(_,value)=><option key={value} value={value}>{value}{value===0?' · 普通':value===9?' · 最高':''}</option>)}</select></label></div><div className="admin-form-actions"><button className="admin-button" type="submit" disabled={busy||!sourceList.length}>创建采集批次（{sourceList.length} 个来源）</button><button className="text-button" type="button" disabled={busy||!sourceList.length} onClick={()=>setSelected({})}>清空选择</button></div><p className="admin-muted">历史窗口对所选历史来源统一生效，终点不包含在窗口中。实时来源持续采集，可单独暂停任务。</p></form>
  </section>
}

function TaskList({cameras,refresh,busy,run,onError,reason,batch,setBatch}){
  const [state,setState]=useState('all'),[camera,setCamera]=useState(''),[cursors,setCursors]=useState([null]),[error,setError]=useState('')
  const query=new URLSearchParams({limit:'20',state,...(camera?{camera_id:camera}:{}),...(batch?{batch_id:batch}:{}),...(cursors.at(-1)?{after:cursors.at(-1)}:{})})
  const page=useIngestionPage('/tasks?'+query,refresh,onError)
  useEffect(()=>setCursors([null]),[batch])
  function filter(setter,value){setter(value);setCursors([null])}
  async function control(task,action){
    setError('')
    try{await run('/tasks/'+task.task_id+'/control',{expected_revision:task.revision,action,reason:reason.trim()})}catch(failure){setError(chineseMessage(failure.message))}
  }
  return <section className="admin-card"><h2 className="help-heading">采集任务<InfoBubble title="使用与限制说明"><p className="admin-muted">“读取完成”只表示采集结束。安全进度表示此前候选已登记；分析数量和当前版本索引回执分别统计；索引回执不包含搜索刷新延迟。</p></InfoBubble></h2><div className="ingestion-inline-form"><label>状态<select value={state} disabled={busy} onChange={event=>filter(setState,event.target.value)}><option value="all">全部</option>{Object.entries(sourceStates).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></label><label>点位<select value={camera} disabled={busy} onChange={event=>filter(setCamera,event.target.value)}><option value="">全部点位</option>{cameras.map(value=><option key={value.camera_id} value={value.camera_id}>{value.display_name}</option>)}</select></label>{batch&&<button type="button" className="text-button" disabled={busy} onClick={()=>setBatch('')}>清除批次筛选 {batch.slice(0,8)}</button>}</div>
    
    {error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{page.error&&<FloatingNotice className="notice error" role="alert">{page.error}</FloatingNotice>}{page.loading&&<p role="status">正在读取采集任务…</p>}
    {page.data&&<><p className="admin-muted">总领取：{page.data.claims_enabled?'已开启':'已关闭'} · 配额：实时 {page.data.policy.rtsp_limit} / 历史 {page.data.policy.archive_limit} / 合计 {page.data.policy.total_limit}</p><div className="admin-table-wrap"><table className="admin-table ingestion-task-table"><thead><tr><th>批次与点位</th><th>采集状态</th><th>读取 / 安全进度</th><th>登记 / 已分析</th><th>重试与操作</th></tr></thead><tbody>{page.data.items.map(task=><tr key={task.task_id}><td><strong>{task.title}</strong><small>{task.display_name} · {task.source_type==='archive'?'历史录像':'实时视频流'}</small><small>{task.task_id.slice(0,8)} · 优先级 {task.priority}</small><button className="text-button" type="button" disabled={busy} onClick={()=>setBatch(task.batch_id)}>查看同批任务</button></td><td>{sourceStates[task.state]}{!task.source_enabled&&<small>来源已停用</small>}<small>更新于 {formatTime(task.updated_at)}</small>{task.last_error_code&&<small className="error">{ingestionErrors[task.last_error_code]||'采集异常，请查看日志。'}</small>}</td><td>{task.source_type==='archive'?<><small>已读：{mediaPosition(task.observed_through_ms)}</small><small>安全：{mediaPosition(task.safe_through_ms)}</small><small>窗口：{mediaPosition(task.start_ms)} — {task.end_ms===null?'片尾':mediaPosition(task.end_ms)}</small></>:<small>持续采集</small>}</td><td><strong>{task.registered_frames} / {task.analyzed_frames}</strong><small>已登记 / 模型已完成</small><small>线索 {task.observations} · 索引回执 {task.indexed_observations}</small></td><td><small>失败 {task.failures} 次</small>{task.state==='queued'&&task.failures>0&&<small>下次尝试 {formatTime(task.next_attempt_at)}</small>}<div className="admin-row-actions">{['queued','active','failed'].includes(task.state)&&<button type="button" className="text-button" disabled={busy||!reason.trim()} onClick={()=>control(task,'pause')}>暂停</button>}{task.state==='disabled'&&<button type="button" className="text-button" disabled={busy||!reason.trim()} onClick={()=>control(task,'resume')}>继续采集</button>}{task.state==='failed'&&<button type="button" className="text-button" disabled={busy||!reason.trim()} onClick={()=>control(task,'retry')}>重试</button>}</div></td></tr>)}</tbody></table></div>{!page.data.items.length&&<p className="admin-empty">当前条件下没有采集任务。</p>}<Paging cursors={cursors} setCursors={setCursors} data={page.data} disabled={busy||page.loading}/></>}
  </section>
}

export default function IngestionJobsPanel({cameras,currentUser,refresh,onError}){
  const [feature,setFeature]=useState(null),[global,setGlobal]=useState(null),[revision,setRevision]=useState(0),[busy,setBusy]=useState(false),[loading,setLoading]=useState(true),[error,setError]=useState(''),[message,setMessage]=useState(''),[reason,setReason]=useState(''),[batch,setBatch]=useState('')
  const report=useRef(onError),working=useRef(false),alive=useRef(true);report.current=onError
  useEffect(()=>{alive.current=true;return()=>{alive.current=false}},[])
  const epoch=`${refresh}:${revision}`
  useEffect(()=>{
    const abort=new AbortController();setLoading(true);setError('')
    businessApi('/api/v1/features',{signal:abort.signal}).then(async value=>{
      if(abort.signal.aborted)return
      if(!value.ingestion_jobs){setFeature(false);setGlobal(null);return}
      const control=await ingestionApi('/control',{signal:abort.signal})
      if(!abort.signal.aborted){setGlobal(control);setFeature(true)}
    }).catch(failure=>{if(!abort.signal.aborted){setError(chineseMessage(failure.message));if(failure.unauthorized)report.current(failure)}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[epoch])
  async function run(path,body,method){
    if(working.current)throw new Error('正在提交另一项操作，请稍后。')
    working.current=true;setBusy(true);setError('');setMessage('')
    try{
      const result=await ingestionCommand(currentUser,path,body,method)
      if(alive.current){setMessage('操作已保存。');setRevision(value=>value+1)}
      return result
    }catch(failure){if(alive.current){setError(chineseMessage(failure.message));if(failure.unauthorized)report.current(failure)}throw failure}
    finally{working.current=false;if(alive.current)setBusy(false)}
  }
  async function globalToggle(){try{await run('/control',{expected_revision:global.revision,enabled:!global.enabled,reason:reason.trim()})}catch{/* The shared command handler displays the error. */}}
  return <><div className="admin-section-title"><div><h1>采集管理</h1><p>管理视频来源、采集批次与安全续跑进度。</p></div><button type="button" className="admin-button secondary" disabled={busy} onClick={()=>setRevision(value=>value+1)}>刷新采集状态</button></div>
    {error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{message&&<FloatingNotice className="admin-success" role="status">{message}</FloatingNotice>}{feature===null&&!error&&<p role="status">正在读取采集配置…</p>}{feature===false&&<p className="admin-empty">采集管理尚未启用。现有搜索和分析任务保持可用。</p>}
    {feature&&<><section className="admin-card"><h2>运行控制</h2><p>总领取开关：<strong>{global.enabled?'已开启':'已关闭'}</strong></p><p className="admin-muted">此开关与现有分析队列共用，只控制新任务领取，不强制结束正在执行的任务，也不会调整 处理节点 副本。来源启用和点位启用分别管理。</p><div className="ingestion-inline-form"><label className="ingestion-reason">本次操作原因<input value={reason} maxLength={300} disabled={busy} placeholder="用于来源启停、任务控制和总开关审计" onChange={event=>setReason(event.target.value)}/></label><button className="admin-button secondary" type="button" disabled={busy||loading||!reason.trim()} onClick={globalToggle}>{global.enabled?'关闭新任务领取':'开启新任务领取'}</button></div><p className="admin-muted">并发上限：实时 {global.policy.rtsp_limit}、历史 {global.policy.archive_limit}、总计 {global.policy.total_limit}；失败最多 {global.policy.max_failures} 次后转人工处理。</p></section>
      <SourceBatch cameras={cameras} refresh={epoch} busy={busy||loading} run={run} onError={onError} reason={reason} onCreated={setBatch}/><TaskList cameras={cameras} refresh={epoch} busy={busy||loading} run={run} onError={onError} reason={reason} batch={batch} setBatch={setBatch}/></>}
  </>
}
