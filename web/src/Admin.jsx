import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React, { useEffect, useRef, useState } from 'react'
import { Login } from './App.jsx'
import { formatTime } from './result-format.js'
import { useModal } from './use-modal.js'
import { moduleNames,taskFailure,taskPhase } from './task-status.js'
import MediaJobsPanel from './MediaJobsPanel.jsx'
import SpecialistCandidatesPanel from './SpecialistCandidatesPanel.jsx'
import {candidateErrors} from './specialist-candidate-client.js'
import CameraGeographyPanel from './CameraGeographyPanel.jsx'
import WorkflowModulesPanel from './WorkflowModulesPanel.jsx'
import ManagedModelsPanel from './ManagedModelsPanel.jsx'
import SpecialistControlPanel from './SpecialistControlPanel.jsx'
import IngestionJobsPanel from './IngestionJobsPanel.jsx'

import AppearanceAdminPanel from './AppearanceAdminPanel.jsx'
import RecordingReviewPanel from './RecordingReviewPanel.jsx'
import {recordingReviewErrors} from './recording-review.js'

const sections = [['overview','运行概览'],['users','用户管理'],['cameras','点位管理'],['geography','点位地图'],['ingestion','采集管理'],['jobs','分析任务'],['modules','模块任务'],['workflow','模块配置'],['recordings','录像回看'],['media','录像制作'],['candidates','车牌候选'],['appearance','外观索引'],['models','模型端点']]
const stateNames = { queued:'排队中',active:'处理中',completed:'已完成',failed:'失败' }
const errors = {...recordingReviewErrors, module_job_unavailable:'该模块任务不可用。',module_queue_full:'模块队列已满，请等待消费后重试。',module_recovery_conflict:'同一重试请求的内容已发生变化，请重新打开任务。',invalid_module_source:'保存结果校验失败，请核查数据完整性后恢复。',camera_revision_conflict:'点位信息已被其他会话修改，请刷新后再保存。',processing_paused:'处理已暂停，恢复处理后才能重试。',state_revision_conflict:'任务状态已变化，请刷新列表。',result_already_present:'该任务已有结果，不能重复执行。',analysis_queue_full:'分析队列已满，请稍后重试。',camera_unavailable:'该点位不可管理。',job_unavailable:'该任务不可用。' }

const userErrors={username_exists:'用户名已存在，请使用其他名称。',user_revision_conflict:'账号资料已更新，请刷新列表后操作。',protected_account:'不能停用、降权或删除受保护账号。',last_admin_required:'系统至少需要保留一个启用的管理员。',user_unavailable:'该账号已不可用。',admin_required:'当前账号没有管理权限。',invalid_user_request:'账号信息无效，请检查填写内容。'}

async function api(path, options={}) {
  const response=await fetch('/api/admin/'+path,{...options,cache:'no-store',headers:{'Content-Type':'application/json',...(options.headers||{})}})
  let data={};try { data=await response.json() } catch { /* show bounded generic error */ }
  if(!response.ok) throw Object.assign(new Error(candidateErrors[data.code] || errors[data.code] || userErrors[data.code] || chineseMessage(data.detail,'管理服务暂不可用，请稍后重试。')),{unauthorized:response.status===401,code:data.code})
  return data
}

function Overview({ refresh,onError }) {
  const [data,setData]=useState(null),[loading,setLoading]=useState(true),[pausing,setPausing]=useState(false)
  const request=useRef(null)
  async function load() {
    request.current?.abort();const controller=new AbortController();request.current=controller;setLoading(true)
    try { const value=await api('overview',{signal:controller.signal});if(request.current===controller) setData(value) }
    catch(error) { if(error.name!=='AbortError' && request.current===controller) onError(error) }
    finally { if(request.current===controller) setLoading(false) }
  }
  useEffect(()=>{load();const timer=setInterval(()=>{if(!document.hidden) load()},15000);return()=>{clearInterval(timer);request.current?.abort();request.current=null}},[refresh])
  const counts=data?.counts||{},states=Object.fromEntries((data?.analysis||[]).map(row=>[row.state,Number(row.count)]))
  const cards=[['监控点位',counts.cameras],['登记关键帧',counts.frames],['语义观测记录',counts.observations],['待分析任务',(states.queued||0)+(states.active||0)],['失败任务',states.failed||0],['待同步索引',counts.pending_index]]
  async function pause() { setPausing(true);try { await api('processing/pause',{method:'POST',body:'{}'});await load() } catch(error) {onError(error)} finally {setPausing(false)} }
  return <><div className="admin-section-title"><div><h1>运行概览</h1><p>当前权限范围内的数据与处理状态。</p></div>{data?.observed_at && <span>{formatTime(data.observed_at)} 更新 · 15 秒刷新</span>}</div>
    {loading&&!data ? <p role="status">正在读取运行数据…</p> : data && <><div className="admin-metrics">{cards.map(([label,value])=><article key={label}><span>{label}</span><strong>{Number(value||0).toLocaleString('zh-CN')}</strong></article>)}</div>
    <section className="admin-card"><div className="admin-card-heading"><h2>处理控制</h2><span className={`admin-badge ${data.analysis_claims_enabled?'running':''}`}>{data.analysis_claims_enabled?'分析领取已启用':'分析领取已暂停'}</span></div><div className="admin-operation-grid"><div><p>已登记接入源 <strong>{counts.sources}</strong> 个 · 已启用 <strong>{counts.enabled_sources}</strong> 个</p><p>任务队列容量 {data.queue_capacity} · 已完成分析任务 {Number(states.completed||0).toLocaleString('zh-CN')}</p>{data.queue_load&&<><p>等待领取 {data.queue_load.ready} · 等待重试 {data.queue_load.retry_waiting} · 处理中 {data.queue_load.active} · 租约待回收 {data.queue_load.expired}</p>{data.queue_policy&&<p className="admin-muted">以上为当前可见点位任务。共享领取配额 {data.queue_policy.active_limit}，失败上限 {data.queue_policy.max_attempts} 次；退避从 {data.queue_policy.retry_base_seconds} 秒起，最长 {data.queue_policy.retry_max_seconds} 秒。</p>}</>}<p className="admin-muted">暂停会停止领取新任务，已领取任务按其实际状态结束；已有线索仍可检索。</p></div><button type="button" className="admin-button secondary" disabled={pausing||(!data.analysis_claims_enabled&&!counts.enabled_sources)} onClick={pause}>{pausing?'正在暂停…':!data.analysis_claims_enabled&&!counts.enabled_sources?'处理已暂停':'暂停处理'}</button></div></section>{data.module_policy&&<ModuleOverview policy={data.module_policy} rows={data.module_load||[]}/>}</>}
  </>
}

function ModuleOverview({policy,rows}) {
  const totals={}
  for(const row of rows)totals[row.module_key+'-'+row.state]=Number(row.count)||0
  return <section className="admin-card"><div className="admin-card-heading"><h2>独立模块任务</h2><span className="admin-muted">共享在途配额 {policy.active_limit}</span></div><p className="admin-muted">当前可见点位的独立模块任务；复用已保存视觉结果，失败模块可单独恢复。</p><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>模块</th><th>排队 / 重试</th><th>处理中</th><th>已完成</th><th>失败</th></tr></thead><tbody>{Object.entries(moduleNames).map(([key,label])=><tr key={key}><th scope="row">{label}</th>{['queued','active','completed','failed'].map(state=><td key={state}>{totals[key+'-'+state]||0}</td>)}</tr>)}</tbody></table></div></section>
}

function draftFor(camera) {
  return {display_name:camera.display_name,location_key:camera.location_key||'',aliases:(camera.aliases||[]).filter(value=>value!==camera.camera_id&&value!==camera.display_name).join('\n'),enabled:camera.enabled}
}

function CameraEditor({ camera,onChanged,onError }) {
  const [baseline,setBaseline]=useState(camera),[draft,setDraft]=useState(()=>draftFor(camera)),[saving,setSaving]=useState(false),[message,setMessage]=useState('')
  const dirty=JSON.stringify(draft)!==JSON.stringify(draftFor(baseline)),conflict=camera.revision!==baseline.revision
  function reset() {setBaseline(camera);setDraft(draftFor(camera));setMessage('')}
  async function save(event) {
    event.preventDefault();setSaving(true);setMessage('')
    const aliases=draft.aliases.split('\n').map(value=>value.trim()).filter(Boolean)
    if(aliases.length>18) {setMessage('检索别名最多填写 18 个。');setSaving(false);return}
    try {
      const result=await api('cameras/'+encodeURIComponent(camera.camera_id),{method:'PUT',body:JSON.stringify({...draft,location_key:draft.location_key.trim()||null,aliases,expected_revision:baseline.revision})})
      setBaseline(result.camera);setDraft(draftFor(result.camera));onChanged(result.camera);setMessage('点位信息已保存。')
    } catch(error) {setMessage(chineseMessage(error.message));if(error.unauthorized) onError(error)} finally {setSaving(false)}
  }
  const id=camera.camera_id
  return <form className="admin-card admin-camera-form" onSubmit={save}><div className="admin-card-heading"><div><h2>{camera.display_name}</h2><p className="admin-muted">{id}</p></div><span className="admin-badge">{camera.enabled?'允许接入':'已停用'}</span></div>
    <label htmlFor={'name-'+id}>点位名称</label><input id={'name-'+id} value={draft.display_name} maxLength={200} required onChange={event=>setDraft({...draft,display_name:event.target.value})}/>
    <label htmlFor={'location-'+id}>所属区域</label><input id={'location-'+id} value={draft.location_key} maxLength={100} placeholder="例如：园区北侧" onChange={event=>setDraft({...draft,location_key:event.target.value})}/>
    <label htmlFor={'aliases-'+id}>检索别名</label><textarea id={'aliases-'+id} rows={3} value={draft.aliases} placeholder="每行一个，例如：北门、1号点位" onChange={event=>setDraft({...draft,aliases:event.target.value})}/><p className="admin-muted">最多 18 个；点位名称和编号会自动作为检索别名。</p>
    <label className="admin-check"><input type="checkbox" checked={draft.enabled} onChange={event=>setDraft({...draft,enabled:event.target.checked})}/>允许该点位接入</label><p className="admin-muted">停用会暂停该点位接入，历史线索保留；启用不自动启动任务。</p>
    {conflict && <FloatingNotice className="error" role="alert">服务器上的点位信息已有更新，请重新载入后编辑。</FloatingNotice>}{message&&<FloatingNotice className={message==='点位信息已保存。'?'admin-success':'error'} role="status">{message}</FloatingNotice>}
    <div className="admin-form-actions"><button className="admin-button" type="submit" disabled={saving||!dirty||conflict}>{saving?'保存中…':'保存点位'}</button><button className="admin-button secondary" type="button" disabled={saving} onClick={reset}>重新载入当前值</button>{dirty&&<span className="admin-muted">有未保存的修改</span>}</div>
  </form>
}

function RetryDialog({ job,onClose,onDone,onError,endpoint='jobs' }) {
  const [reason,setReason]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('')
  const panel=useModal(()=>{if(!busy)onClose()})
  const intent=useRef(null)
  async function retry(event) {
    event.preventDefault();const submitted=reason.trim()
    if(!submitted||/[\u0000-\u001f\u007f]/.test(submitted)){setError('重试原因请使用单行文字。');return}
    if(intent.current?.reason!==submitted)intent.current={request_id:crypto.randomUUID(),expected_revision:job.revision,reason:submitted}
    setBusy(true);setError('')
    try {await api(endpoint+'/'+job.job_id+'/retry',{method:'POST',body:JSON.stringify(intent.current)});onDone();onClose()}
    catch(value){setError(chineseMessage(value.message));if(value.unauthorized)onError(value)} finally {setBusy(false)}
  }
  return <div className="dialog-backdrop" onMouseDown={event=>{if(event.target===event.currentTarget&&!busy) onClose()}}><section className="admin-retry-dialog" ref={panel} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="retry-title"><div className="admin-card-heading"><h2 id="retry-title">{endpoint==='module-jobs'?'重试模块任务':'重试分析任务'}</h2><button className="dialog-close" onClick={onClose} disabled={busy} aria-label="关闭重试">×</button></div><p className="admin-muted">{job.camera_name||job.camera_id} · {job.job_id}</p><form onSubmit={retry}><label htmlFor="retry-reason">重试原因</label><textarea id="retry-reason" required maxLength={300} rows={3} value={reason} onChange={event=>setReason(event.target.value)}/>{error&&<FloatingNotice className="error" role="alert">{error}</FloatingNotice>}<button className="admin-button" disabled={busy||!reason.trim()}>{busy?'正在提交…':'提交重试'}</button></form></section></div>
}

function Jobs({ cameras,refresh,onError,taskKind='frame' }) {
  const [state,setState]=useState('failed'),[camera,setCamera]=useState(''),[cursors,setCursors]=useState([null]),[page,setPage]=useState(0)
  const [data,setData]=useState(null),[loading,setLoading]=useState(false),[retry,setRetry]=useState(null),[revision,setRevision]=useState(0)
  const isModule=taskKind==='module',endpoint=isModule?'module-jobs':'jobs'
  const [module,setModule]=useState('')
  useEffect(()=>{
    const controller=new AbortController();setLoading(true)
    const query=new URLSearchParams({state,limit:'20',...(isModule&&module?{module_key:module}:{}),...(camera?{camera}:{}),...(cursors[page]?{after:cursors[page]}:{})})
    api(endpoint+'?'+query,{signal:controller.signal}).then(value=>{if(!controller.signal.aborted)setData(value)}).catch(error=>{if(error.name!=='AbortError') onError(error)}).finally(()=>{if(!controller.signal.aborted)setLoading(false)})
    return()=>controller.abort()
  },[state,camera,module,page,revision,refresh,endpoint])
  function filter(change,value) {setLoading(true);change(value);setCursors([null]);setPage(0);setData(null)}
  function next() {if(!data?.next_cursor||loading) return;setLoading(true);setCursors(values=>[...values.slice(0,page+1),data.next_cursor]);setPage(value=>value+1)}
  return <><div className="admin-section-title"><div><h1>{isModule?'模块任务':'分析任务'}</h1><p>{isModule?'分别查看人物、车辆和场景模块；单独重试，不重复视觉推理。':'查看帧分析和结果提交状态；后续模块进度请查看“模块任务”。'}</p></div></div><section className="admin-card"><div className="admin-filters"><label>任务状态<select value={state} onChange={event=>filter(setState,event.target.value)}><option value="all">全部状态</option>{Object.entries(stateNames).map(([value,name])=><option key={value} value={value}>{name}</option>)}</select></label><label>点位<select value={camera} onChange={event=>filter(setCamera,event.target.value)}><option value="">全部点位</option>{cameras.map(item=><option key={item.camera_id} value={item.camera_id}>{item.display_name}</option>)}</select></label>{isModule&&<label>模块类型<select value={module} onChange={event=>filter(setModule,event.target.value)}><option value="">全部模块</option>{Object.entries(moduleNames).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label>}<span className="admin-muted" role="status">{loading?'正在读取…':data?.processing_paused?'处理已暂停，重试暂不可用':''}</span></div>
    <div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>点位 / 任务</th><th>状态</th><th>失败次数</th><th>最后失败原因</th><th>更新时间</th><th>操作</th></tr></thead><tbody>{(loading?[]:data?.items||[]).map(job=><tr key={job.job_id}><td><strong>{job.camera_name||job.camera_id}</strong>{isModule&&<p className="admin-muted">{moduleNames[job.module_key]||'其他分析模块'}</p>}<details><summary>任务编号</summary><code>{job.job_id}</code><p>帧编号：{job.frame_id}</p>{isModule&&<><p>结果源：{job.source_id}</p><p>模型：{job.model_id} · {job.prompt_version}</p><p>输入摘要：<code>{job.source_sha256}</code></p></>}</details></td><td><span className={`admin-badge status-${job.state}`}>{taskPhase(job,data.processing_paused).label}</span>{taskPhase(job).phase==='retry_wait'&&<p className="admin-muted">最早重试：{formatTime(job.next_attempt_at)||'时间未知'}{data.processing_paused?'，恢复处理后生效':''}</p>}</td><td>{job.attempts}{data.queue_policy&&` / ${data.queue_policy.max_attempts}`}</td><td>{taskFailure(job.last_error_code)}{job.last_error_code&&<details><summary>诊断说明</summary><span>{taskFailure(job.last_error_code)}</span></details>}</td><td>{formatTime(job.updated_at)||'—'}</td><td><button type="button" className="admin-button secondary compact" disabled={!job.can_retry||loading} onClick={()=>setRetry(job)} title={job.can_retry?'按当前任务版本重试':'当前状态不允许重试'}>重试</button></td></tr>)}</tbody></table></div>
    {!loading&&!data?.items?.length&&<p className="admin-empty">当前条件下没有任务。</p>}<div className="admin-list-paging"><button type="button" className="admin-button secondary" disabled={loading||!page} onClick={()=>{setLoading(true);setPage(value=>value-1)}}>上一页</button><span>第 {page+1} 页</span><button type="button" className="admin-button secondary" disabled={loading||!data?.has_more} onClick={next}>下一页</button></div></section>
    {retry&&<RetryDialog endpoint={endpoint} job={retry} onClose={()=>setRetry(null)} onDone={()=>{setPage(0);setCursors([null]);setRevision(value=>value+1)}} onError={onError}/>}</>
}

function Models({refresh,onError}) {
  return <ManagedModelsPanel refresh={refresh} onError={onError} api={api}/>
}

function UserDialog({ mode,user,currentUser,onClose,onDone,onError }) {
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[password,setPassword]=useState('')
  const [name,setName]=useState(''),[displayName,setDisplayName]=useState(user?.display_name||''),[enabled,setEnabled]=useState(user?.enabled??true),[administrator,setAdministrator]=useState(user?.is_admin||false)
  const panel=useModal(()=>{if(!busy)onClose()}),protectedAccount=user?.username==='admin'||user?.username===currentUser
  const titles={create:'新增用户',edit:'编辑用户',password:'重置密码',delete:'删除用户'}
  async function submit(event) {
    event.preventDefault();setBusy(true);setError('')
    try {
      const path='users'+(mode==='create'?'':'/'+encodeURIComponent(user.username))+(mode==='password'?'/password':'')
      const body=mode==='create'?{username:name.trim(),display_name:displayName.trim(),password,is_admin:administrator}:mode==='password'?{password,expected_version:user.version}:mode==='delete'?{expected_version:user.version}:{display_name:displayName.trim(),enabled,is_admin:administrator,expected_version:user.version}
      await api(path,{method:mode==='edit'?'PUT':mode==='delete'?'DELETE':'POST',body:JSON.stringify(body)})
      setPassword('');onDone(mode,user);onClose()
    } catch(value){setError(chineseMessage(value.message));if(value.unauthorized)onError(value)} finally{setBusy(false)}
  }
  return <div className="dialog-backdrop" onMouseDown={event=>{if(event.target===event.currentTarget&&!busy)onClose()}}><section className="admin-retry-dialog" ref={panel} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="user-dialog-title"><div className="admin-card-heading"><h2 id="user-dialog-title">{titles[mode]}</h2><button className="dialog-close" type="button" disabled={busy} onClick={onClose} aria-label="关闭用户操作">×</button></div><form onSubmit={submit}>
    {mode==='create'?<><label htmlFor="new-username">用户名</label><input id="new-username" value={name} minLength={3} maxLength={80} pattern="[A-Za-z0-9_.@-]{3,80}" autoComplete="off" required onChange={event=>setName(event.target.value)}/><p className="admin-muted">3–80 位字母、数字或 . _ @ -，不区分大小写。</p></>:<p className="admin-muted">账号：{user.username}</p>}
    {(mode==='create'||mode==='edit')&&<><label htmlFor="user-display-name">显示名称</label><input id="user-display-name" value={displayName} maxLength={100} required onChange={event=>setDisplayName(event.target.value)}/><label className="admin-check"><input type="checkbox" checked={administrator} disabled={protectedAccount&&mode==='edit'} onChange={event=>setAdministrator(event.target.checked)}/>管理员账号</label><p className="admin-muted">普通用户使用线索搜索，管理员可进入管理后台。</p></>}
    {mode==='edit'&&<label className="admin-check"><input type="checkbox" checked={enabled} disabled={protectedAccount} onChange={event=>setEnabled(event.target.checked)}/>启用账号</label>}
    {(mode==='create'||mode==='password')&&<><label htmlFor="new-user-password">{mode==='create'?'初始密码':'新密码'}</label><input id="new-user-password" type="password" autoComplete="new-password" minLength={8} maxLength={128} value={password} required onChange={event=>setPassword(event.target.value)}/><p className="admin-muted">密码为 8–128 位。{mode==='password'?'重置后原登录会话失效，需要重新登录。':''}</p></>}
    {mode==='delete'&&<p>删除后该账号将无法登录，原有审计记录保留。</p>}{error&&<FloatingNotice className="error" role="alert">{error}</FloatingNotice>}<div className="admin-form-actions"><button className="admin-button" type="submit" disabled={busy}>{busy?'正在保存…':mode==='delete'?'删除账号':'保存'}</button><button className="admin-button secondary" type="button" disabled={busy} onClick={onClose}>取消</button></div>
  </form></section></div>
}

function Users({ currentUser,refresh,onError }) {
  const [data,setData]=useState(null),[loading,setLoading]=useState(true),[dialog,setDialog]=useState(null),[revision,setRevision]=useState(0),[cursors,setCursors]=useState([null]),[page,setPage]=useState(0),[message,setMessage]=useState('')
  useEffect(()=>{const controller=new AbortController();setLoading(true);const query=new URLSearchParams({limit:'50',...(cursors[page]?{after:cursors[page]}:{})});api('users?'+query,{signal:controller.signal}).then(value=>{if(!controller.signal.aborted)setData(value)}).catch(error=>{if(error.name!=='AbortError')onError(error)}).finally(()=>{if(!controller.signal.aborted)setLoading(false)});return()=>controller.abort()},[refresh,revision,page])
  function done(mode,user) {if(mode==='password'&&user?.username===currentUser){onError({unauthorized:true});return}setMessage('账号操作已保存。');setPage(0);setCursors([null]);setRevision(value=>value+1)}
  function next(){if(!data?.next_cursor||loading)return;setLoading(true);setCursors(values=>[...values.slice(0,page+1),data.next_cursor]);setPage(value=>value+1)}
  return <><div className="admin-section-title"><div><h1>用户管理</h1><p>新增账号、启用停用及重置密码。</p></div><button type="button" className="admin-button" onClick={()=>setDialog({mode:'create'})}>新增用户</button></div>{message&&<FloatingNotice className="admin-success" role="status">{message}</FloatingNotice>}<section className="admin-card"><div className="admin-table-wrap" aria-busy={loading}><table className="admin-table"><thead><tr><th>用户名</th><th>显示名称</th><th>类型</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead><tbody>{(!loading?data?.items||[]:[]).map(user=><tr key={user.username}><td><strong>{user.username}</strong>{user.username===currentUser&&<small className="admin-muted"> · 当前账号</small>}</td><td>{user.display_name}</td><td>{user.is_admin?'管理员':'普通用户'}</td><td><span className="admin-badge">{user.enabled?'已启用':'已停用'}</span></td><td>{formatTime(user.updated_at)}</td><td><div className="admin-row-actions"><button type="button" className="text-button" onClick={()=>setDialog({mode:'edit',user})}>编辑</button><button type="button" className="text-button" onClick={()=>setDialog({mode:'password',user})}>重置密码</button>{user.username!=='admin'&&user.username!==currentUser&&<button type="button" className="text-button" onClick={()=>setDialog({mode:'delete',user})}>删除</button>}</div></td></tr>)}</tbody></table></div>{loading?<p className="admin-muted" role="status">正在读取账号…</p>:!data?.items?.length&&<p className="admin-empty">暂无用户。</p>}<div className="admin-list-paging"><button type="button" className="admin-button secondary" disabled={loading||!page} onClick={()=>{setLoading(true);setPage(value=>value-1)}}>上一页</button><span>第 {page+1} 页</span><button type="button" className="admin-button secondary" disabled={loading||!data?.has_more} onClick={next}>下一页</button></div></section>{dialog&&<UserDialog {...dialog} currentUser={currentUser} onClose={()=>setDialog(null)} onDone={done} onError={onError}/>}</>
}

export default function Admin() {
  const [authenticated,setAuthenticated]=useState(null),[username,setUsername]=useState(''),[cameras,setCameras]=useState([]),[error,setError]=useState(''),[refresh,setRefresh]=useState(0),[connectionError,setConnectionError]=useState('')
  const [section,setSection]=useState(()=>sections.some(([id])=>id===location.hash.slice(1))?location.hash.slice(1):'overview')
  async function checkSession(){setConnectionError('');try{const response=await fetch('/api/session');if(!response.ok)throw new Error('登录验证服务暂不可用，请重新连接。');const value=await response.json();if(value.authenticated&&value.is_admin===false){location.replace('/');return}setAuthenticated(value.authenticated);if(value.username)setUsername(value.username)}catch(value){setConnectionError(chineseMessage(value.message))}}
  useEffect(()=>{checkSession()},[])
  function onError(value) {if(value.unauthorized){setAuthenticated(false);setCameras([]);setUsername('')}else setError(chineseMessage(value.message))}
  useEffect(()=>{if(!authenticated)return;const controller=new AbortController();Promise.all([api('config',{signal:controller.signal}),api('cameras',{signal:controller.signal})]).then(([config,value])=>{if(!controller.signal.aborted){setUsername(config.username);setCameras(value.items)}}).catch(value=>{if(value.name!=='AbortError')onError(value)});return()=>controller.abort()},[authenticated,refresh])
  function select(id) {setSection(id);setError('');history.replaceState(null,'','#'+id)}
  async function logout(){await fetch('/api/session',{method:'DELETE'});setAuthenticated(false);setCameras([]);setUsername('')}
  if(authenticated===null)return <main className="starting" role="status"><div>{connectionError||'正在连接管理后台…'}{connectionError&&<button type="button" className="text-button" onClick={checkSession}>重新连接</button>}</div></main>
  if(!authenticated)return <Login context="admin" onLogin={session=>{if(session?.is_admin===false){location.replace('/');return}setAuthenticated(true);setUsername(session?.username||'');setError('')}}/>
  return <div className="admin-app"><aside className="admin-sidebar"><a className="admin-brand" href="/">视迹 <span>管理后台</span></a><nav aria-label="管理功能">{sections.map(([id,label])=><button type="button" key={id} className={section===id?'is-active':''} aria-current={section===id?'page':undefined} onClick={()=>select(id)}>{label}</button>)}</nav><a className="admin-return" href="/">返回线索搜索</a></aside><div className="admin-main"><header className="admin-topbar"><span>{username||'管理员'}</span><div><button type="button" className="text-button" onClick={()=>{setError('');setRefresh(value=>value+1)}}>刷新数据</button><button type="button" className="text-button" onClick={logout}>退出登录</button></div></header><main className="admin-content">{error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{section==='overview'&&<Overview refresh={refresh} onError={onError}/>} {section==='cameras'&&<><div className="admin-section-title"><div><h1>点位管理</h1><p>维护点位名称、区域和自然语言检索别名。</p></div></div><div className="admin-camera-grid">{cameras.map(camera=><CameraEditor key={camera.camera_id} camera={camera} onChanged={changed=>setCameras(values=>values.map(value=>value.camera_id===changed.camera_id?changed:value))} onError={onError}/>)}</div>{!cameras.length&&<p className="admin-empty">当前没有可管理的点位。</p>}</>}{section==='users'&&<Users currentUser={username} refresh={refresh} onError={onError}/>}{section==='jobs'&&<Jobs cameras={cameras} refresh={refresh} onError={onError}/>}{section==='modules'&&<Jobs taskKind="module" cameras={cameras} refresh={refresh} onError={onError}/>} {section==='recordings'&&<RecordingReviewPanel cameras={cameras} refresh={refresh} onError={onError} request={api}/>} {section==='media'&&<MediaJobsPanel cameras={cameras} refresh={refresh} onError={onError}/>} {section==='candidates'&&<><SpecialistControlPanel refresh={refresh} onError={onError} request={api}/><SpecialistCandidatesPanel cameras={cameras} refresh={refresh} onError={onError} request={api}/></>} {section==='workflow'&&<WorkflowModulesPanel refresh={refresh} onError={onError}/>} {section==='ingestion'&&<IngestionJobsPanel cameras={cameras} currentUser={username} refresh={refresh} onError={onError}/>} {section==='geography'&&<CameraGeographyPanel refresh={refresh} onError={onError}/>} {section==='appearance'&&<AppearanceAdminPanel refresh={refresh} onError={onError}/>} {section==='models'&&<Models refresh={refresh} onError={onError}/>}</main></div></div>
}
