import WorkspaceHeader,{BrandMark,BrandWordmark} from './WorkspaceHeader.jsx'
import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React, { useEffect, useRef, useState } from 'react'
import PasswordLogin from './PasswordLogin.jsx'
import { readSearchResponse } from './search-response.js'
import SearchGuidance,{SearchClarification} from './SearchGuidance.jsx'
import {validateSearchResult,canUseKeywordSearch,searchRequestBody} from './search-guidance.js'
import EvidenceDialog from './EvidenceDialog.jsx'
import { formatTime, sourceStateLabel, uncertainLabels } from './result-format.js'
import VideoDialog from './VideoDialog.jsx'
import ImageSearch from './ImageSearch.jsx'

function SearchIcon({ size = 22 }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="10.7" cy="10.7" r="6.7" stroke="currentColor" strokeWidth="1.7"/><path d="m16 16 4.2 4.2" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round"/></svg>
}

function Brand() { return <div className="brand"><BrandWordmark/></div> }

async function responseJson(response) {
  let data = {}
  try { data = await response.json() } catch { /* show the generic error below */ }
  if (!response.ok) {
    const messages = {
      model_unavailable: '搜索理解服务暂时不可用。可先试试“查找车辆”或“穿黑色上衣的人”，也可以稍后重试。',
      invalid_model_plan: '暂时无法理解这次描述，请明确目标及特征后重试。',
      invalid_search_scope: '无法确定查询范围，请检查点位或地点描述。',
      search_backend_unavailable: '线索索引暂时不可用，请稍后重试。',
      plate_search_changed: '车牌复核或来源已变化，请重新搜索。',
      reviewed_plate_search_disabled: '已复核车牌检索暂未启用，请稍后再试。',
      search_cursor_expired: '翻页会话已过期，请重新搜索。',
      invalid_search_cursor: '查询条件或翻页会话已变化，请重新搜索。',
      search_context_expired: '条件调整已过期，请重新理解原描述后再搜索。',
      invalid_search_context: '检索条件已失效，请重新理解原描述。',
      invalid_search_adjustment: '无法应用这次条件调整，请重新读取或修改描述。',
      search_scope_changed: '地点对应的点位已变化，请重新理解原描述。',
      access_unavailable: '试用连接暂时中断，正在自动重连，请稍后重新搜索。',
    }
    throw Object.assign(new Error(messages[data.code] || chineseMessage(data.detail,'服务暂时不可用，请稍后重试。')),{code:data.code})
  }
  return data
}

export function Login({ onLogin, context = 'search' }) {
  const [token, setToken] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [mode, setMode] = useState(null)
  useEffect(() => { fetch('/api/auth/config').then(responseJson).then(data => { if (!['token','password'].includes(data.mode)) throw new Error('登录配置不可用'); setMode(data.mode) }).catch(e => setError(chineseMessage(e.message))) }, [])
  async function submit(event) {
    event.preventDefault()
    if (!token.trim()) return
    setBusy(true); setError('')
    try {
      const session = await responseJson(await fetch('/api/session', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }) }))
      setToken(''); onLogin(session)
    } catch (e) { setError(chineseMessage(e.message)) } finally { setBusy(false) }
  }
  if (mode === 'password') return <PasswordLogin onLogin={onLogin} context={context} Brand={Brand} responseJson={responseJson}/>
  if (mode === null) return <main className="starting" role="status">{error || '正在连接登录服务…'}</main>
  return <main className="access-wrap"><section className="access-card"><Brand/><div className="access-copy"><p className="eyebrow">受控试用环境</p><h1>{context === 'evaluation' ? '评测样本标注' : '欢迎使用视迹'}</h1><p>请输入试用访问令牌，进入{context === 'evaluation' ? '评测工作台' : context === 'admin' ? '管理后台' : '视频线索搜索'}。</p></div><form onSubmit={submit}><label htmlFor="access-token">访问令牌</label><input id="access-token" type="password" autoComplete="current-password" value={token} onChange={e => setToken(e.target.value)} required /><button className="primary" type="submit" disabled={busy}>{busy ? '正在验证…' : context === 'evaluation' ? '进入评测' : '进入搜索'}</button>{error && <FloatingNotice className="error" role="alert">{error}</FloatingNotice>}</form></section></main>
}

function terms(plan) {
  if (!plan) return []
  const a = plan.attributes || {}
  const target = plan.targetType === 'person' ? '人' : plan.targetType === 'vehicle' ? '车辆' : null
  const values = [target, plan.locationText, a.upperColor && `上衣 ${a.upperColor}`, a.lowerColor && `下装 ${a.lowerColor}`,
    a.clothingColor && `衣着 ${a.clothingColor}`, a.vehicleColor && `车身 ${a.vehicleColor}`, ...(a.carrying || []), a.transportType, a.visibleAction,...(plan.textTerms||[]),...(plan.cameraIds||[])]
  if (plan.start || plan.end) values.push(`${formatTime(plan.start) || '不限开始'} 至 ${formatTime(plan.end) || '不限结束'}`)
  return values.filter(Boolean)
}

function ResultCard({ item, onInspect, onPlay }) {
  const [imageFailed, setImageFailed] = useState(false)
  const time = item.source_time ? formatTime(item.source_time) : formatTime(item.received_at)
  const label = item.source_time ? '来源时间' : '记录时间'
  const tags = [item.target_type === 'person' && item.upper_color && `上衣 ${item.upper_color}`, item.target_type === 'person' && item.lower_color && `下装 ${item.lower_color}`, item.vehicle_color && `车身 ${item.vehicle_color}`,
    item.reviewed_plate_number && `已复核车牌 ${item.reviewed_plate_number}`,
    ...(Array.isArray(item.carrying) ? item.carrying : []), item.transport_type, item.visible_action].filter(Boolean)
  const uncertain = uncertainLabels(item.uncertain_fields)
  const imageUrl = item.frame_id ? `/api/v1/frames/${encodeURIComponent(item.frame_id)}/image` : null
  return <article className="result-card">{imageUrl && !imageFailed ? <button type="button" className="result-image" onClick={() => onInspect(item)} aria-label="查看线索和原始关键帧"><img src={imageUrl} alt="视频关键帧" loading="lazy" onError={() => setImageFailed(true)}/></button> : <div className="result-leading" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><rect x="3" y="5" width="14" height="14" rx="3" stroke="currentColor" strokeWidth="1.5"/><path d="m17 10 4-2v8l-4-2" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/></svg></div>}<div className="result-content"><div className="result-meta"><strong>{item.camera_name || item.camera_id || '未命名点位'}</strong><span>{label}{time ? ` · ${time}` : ''}</span></div><h3>{item.description || (item.target_type === 'vehicle' ? '发现车辆线索' : '发现人物线索')}</h3>{item.match_reasons?.length > 0 && <p className="match-reasons">相关特征：{item.match_reasons.join('、')}</p>}{Number.isFinite(item.relevance_score) && item.relevance_score > 0 && <p className="result-note">相关度 {item.relevance_score.toFixed(2)}</p>}{tags.length > 0 && <div className="tags">{tags.map((tag, i) => <span key={`${tag}-${i}`}>{tag}</span>)}</div>}<div className="result-actions"><InfoBubble title="线索依据与限制"><p className="result-note">模型生成的线索，需结合原始画面人工核验</p>{item.match_note && <p className="result-note">{item.match_note}</p>}{uncertain.length > 0 && <p className="result-note">无法确定：{uncertain.join('、')}</p>}</InfoBubble><div className="result-action-buttons">{item.observation_id && <button type="button" className="play-button" onClick={() => onInspect(item)}>查看详情</button>}{item.frame_id && <button type="button" className="play-button" onClick={() => onPlay(item)}>回看视频</button>}</div></div></div></article>
}

export {default as VideoDialog} from './VideoDialog.jsx'

function ProcessingStatus({ data, error, loading, onRefresh, onClose }) {
  const queue = data?.queue, pipeline = data?.pipeline, sources = data?.sources || []
  return <aside className="status-panel" role="region" aria-label="点位处理状态"><div className="status-heading"><strong>点位处理状态</strong><button className="text-button" onClick={onClose} aria-label="关闭状态">关闭</button></div>
    {loading && !data && <p className="status-message" role="status">正在读取状态…</p>}{error && <FloatingNotice className="error" role="alert">{error}</FloatingNotice>}
    {data && <><p className="status-message">{chineseMessage(data.message,'部分来源状态暂时无法读取。')}</p>{pipeline && <p className="pipeline-status">{pipeline.capture_state === 'paused' ? '采集已暂停' : pipeline.capture_state === 'enabled' ? '采集已启用' : '尚无视频源'} · {pipeline.analysis_claims_enabled === false ? '分析领取已暂停' : pipeline.analysis_claims_enabled === true ? '分析领取已启用' : '分析领取状态未知'}</p>}
    {queue && <p className={`status-queue ${queue.level === 'high' || queue.level === 'full' ? 'status-queue-warning' : ''}`}>当前范围待分析 {queue.scope_pending}{Number.isInteger(queue.capacity) ? ` / ${queue.capacity}` : ''}{queue.level === 'high' ? ' · 积压较高' : queue.level === 'full' ? ' · 队列已满，采样可能中断' : ''}{queue.oldest_pending_at && ` · 最早入队 ${formatTime(queue.oldest_pending_at)}`}</p>}
    <div className="status-list">{sources.map((source,i) => <div className="status-row" key={`${source.camera_id}-${source.source_type}-${i}`}><div><strong>{source.camera_name || source.camera_id} · {source.source_type === 'archive' ? '历史录像' : '实时流'}</strong><span className={`source-state source-state-${source.processing_state}`}>{sourceStateLabel(source)}</span></div><p>登记 {source.sampled} · 已分析 {source.analyzed} · 排队 {source.pending} · 失败 {source.failed}</p>{source.latest_received_at && <p>最近入队 {formatTime(source.latest_received_at)}</p>}</div>)}</div>
    {!sources.length && <p className="status-message">当前权限范围内尚无已登记视频源。</p>}{data.observed_at && <p className="status-message">更新于 {new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date(data.observed_at))} · 打开时每 15 秒刷新</p>}</>}
    <button className="status-refresh" onClick={onRefresh} disabled={loading}>{loading ? '更新中…' : '刷新状态'}</button></aside>
}

function App() {
  const [appearanceEnabled,setAppearanceEnabled]=useState(false)
  const [liveEnabled,setLiveEnabled]=useState(false)
  const [authenticated, setAuthenticated] = useState(null)
  const [isAdmin,setIsAdmin] = useState(false), [connectionError,setConnectionError] = useState('')
  const [cluesEnabled,setCluesEnabled] = useState(false)
  const [query, setQuery] = useState(()=>new URLSearchParams(location.search).get('q')?.slice(0,1000)||'')
  const [searchMode,setSearchMode]=useState(()=>window.location.pathname.replace(/\/$/,'')==='/image-search'||new URLSearchParams(window.location.search).get('mode')==='image'?'image':'text')
  const [imageFile,setImageFile]=useState(null),imagePicker=useRef(null)
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [restartRequired,setRestartRequired]=useState(false)
  const [errorCode,setErrorCode]=useState(null),[keywordActive,setKeywordActive]=useState(false)
  const [notice,setNotice] = useState(''), [selected,setSelected] = useState(null), [playerItem,setPlayerItem] = useState(null)
  const [statusOpen, setStatusOpen] = useState(false)
  const [statusData, setStatusData] = useState(null)
  const [statusError, setStatusError] = useState('')
  const [statusLoading, setStatusLoading] = useState(false)
  const searchInput = useRef(null)
  const [mapEnabled,setMapEnabled]=useState(false)
  const request = useRef(null)
  const resultSnapshot = useRef(null)
  const statusRequest = useRef(null)
  const lastAttempt=useRef(null),scrollRequest=useRef(null)
  async function checkSession() { setConnectionError(''); try { const data=await responseJson(await fetch('/api/session')); setAuthenticated(data.authenticated); setIsAdmin(data.authenticated && data.is_admin !== false) } catch(error) { setConnectionError(chineseMessage(error.message)) } }
  useEffect(() => { checkSession();if(window.location.pathname.replace(/\/$/,'')==='/image-search')window.history.replaceState(null,'','/?mode=image') }, [])
  useEffect(()=>{setCluesEnabled(false);setAppearanceEnabled(false);setLiveEnabled(false);setMapEnabled(false);if(!authenticated)return;const controller=new AbortController();fetch('/api/v1/features',{signal:controller.signal,cache:'no-store'}).then(response=>response.ok?response.json():{}).then(value=>{if(!controller.signal.aborted){setMapEnabled(value.investigation===true);setCluesEnabled(value.clue_collections===true);setAppearanceEnabled(value.appearance===true);setLiveEnabled(value.live_view===true)}}).catch(()=>{});return()=>controller.abort()},[authenticated])
  useEffect(() => {
    function shortcut(event) { if (event.key === '/' && !document.querySelector('[role="dialog"][aria-modal="true"]') && !['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName)) { event.preventDefault(); searchInput.current?.focus() } }
    window.addEventListener('keydown', shortcut)
    return () => { window.removeEventListener('keydown', shortcut); const active=request.current;request.current=null;active?.abort();if(scrollRequest.current!==null)cancelAnimationFrame(scrollRequest.current);releaseSearch() }
  }, [])
  function closeSnapshot(snapshot){
    if(snapshot?.pagination?.close_cursor&&typeof snapshot.query==='string')fetch('/api/v1/search/close',{method:'POST',keepalive:true,headers:{'Content-Type':'application/json'},body:JSON.stringify({query:snapshot.query,cursor:snapshot.pagination.close_cursor})}).catch(()=>{ /* ES PIT TTL bounds cleanup when the connection is unavailable. */ })
  }
  function releaseSearch() {
    const old = resultSnapshot.current
    resultSnapshot.current = null
    closeSnapshot(old)
  }
  function textMode(){setSearchMode('text');setImageFile(null);setError('')}
  function imageMode(){if(loading)cancelSearch();releaseSearch();setResult(null);setError('');setSearchMode('image');imagePicker.current?.click()}
  async function submit(event) {
    event.preventDefault()
    const text = query.trim()
    if (searchMode!=='text'||!text||loading) return
    releaseSearch()
    await runSearch(text, null)
  }
  async function changePage(cursor) {
    if (loading || !cursor || !result?.query) return
    await runSearch(result.query, cursor)
  }
  function adjustSearch(text,adjustment){if(!loading)return runSearch(text,null,adjustment)}
  function retrySearch(event){
    event.preventDefault();if(loading||!lastAttempt.current)return
    const attempt=lastAttempt.current
    if(restartRequired){chooseExample(attempt.text);return}
    return runSearch(attempt.text,attempt.cursor,attempt.adjustment,attempt.mode)
  }
  function searchKeywords(){
    if(loading||!canUseKeywordSearch(errorCode,query,lastAttempt.current?.text))return
    const text=lastAttempt.current.text;releaseSearch();return runSearch(text,null,null,'keyword')
  }
  async function runSearch(text, cursor,adjustment=null,mode='natural') {
    request.current?.abort(); const controller = new AbortController(); request.current = controller
    lastAttempt.current={text,cursor,adjustment,mode}
    if(scrollRequest.current!==null){cancelAnimationFrame(scrollRequest.current);scrollRequest.current=null}
    setLoading(true); setError('');setErrorCode(null);setRestartRequired(false); setNotice(''); setSelected(null); setPlayerItem(null); if (!cursor&&!adjustment){setResult(null);setKeywordActive(false)}
    try {
      const response = await fetch('/api/v1/search', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify(searchRequestBody(text,cursor,adjustment,mode)), signal: controller.signal })
      if (response.status === 401) { if(request.current===controller)loseAuthentication(); return }
      const raw = await readSearchResponse(response, responseJson)
      let data;try{data=validateSearchResult(raw,text)}catch(problem){if(!cursor)closeSnapshot(raw);throw problem}
      if (request.current === controller) {
        if(!cursor)closeSnapshot(resultSnapshot.current)
        resultSnapshot.current = data; setResult(data)
        if(!cursor&&!adjustment)setKeywordActive(mode==='keyword')
        if(adjustment)setNotice(data.status==='clarification_required'?'条件已调整，请继续补充尚未明确的信息。':'已按调整后的条件重新检索。')
        if (cursor)scrollRequest.current=requestAnimationFrame(()=>{scrollRequest.current=null;if(request.current===controller)document.getElementById('search-results')?.scrollIntoView({block:'start'})})
      }else if(!cursor)closeSnapshot(data)
    } catch (e) { if (request.current === controller && e.name !== 'AbortError'){setError(chineseMessage(e.message));setErrorCode(e.code);setRestartRequired(['plate_search_changed','search_context_expired','invalid_search_context','search_scope_changed','search_cursor_expired','invalid_search_cursor'].includes(e.code))} } finally { if (request.current === controller) setLoading(false) }
  }
  async function refreshStatus() {
    statusRequest.current?.abort(); const controller = new AbortController(); statusRequest.current = controller
    setStatusLoading(true); setStatusError('')
    try {
      const response = await fetch('/api/v1/coverage', { signal:controller.signal, cache:'no-store' })
      if (response.status === 401) { if(statusRequest.current===controller)loseAuthentication(); return }
      const data = await responseJson(response)
      if (statusRequest.current === controller) setStatusData(data)
    } catch (e) { if (statusRequest.current === controller && e.name !== 'AbortError') setStatusError(chineseMessage(e.message)) }
    finally { if (statusRequest.current === controller) setStatusLoading(false) }
  }
  useEffect(() => {
    if (!statusOpen) return
    refreshStatus()
    const timer = setInterval(() => { if (!document.hidden) refreshStatus() }, 15000)
    return () => { clearInterval(timer); statusRequest.current?.abort(); statusRequest.current = null; setStatusLoading(false) }
  }, [statusOpen])
  function toggleStatus() { setStatusOpen(value => !value) }
  function loseAuthentication() { const active=request.current;request.current=null;active?.abort();statusRequest.current?.abort();statusRequest.current=null;lastAttempt.current=null;releaseSearch();setLoading(false);setStatusLoading(false);setStatusError('');setError('');setErrorCode(null);setKeywordActive(false);setNotice('');setRestartRequired(false);setAuthenticated(false); setIsAdmin(false); setSelected(null); setPlayerItem(null); setResult(null); setStatusOpen(false); setStatusData(null) }
  function cancelSearch() { request.current?.abort(); request.current = null; setLoading(false); setNotice('已停止等待，可重新搜索。') }
  function chooseExample(text) { if(loading)return;setQuery(text); releaseSearch(); runSearch(text,null) }
  async function logout() {
    const active=request.current;request.current=null;active?.abort();setLoading(false);releaseSearch();setSelected(null);setPlayerItem(null)
    try{const response=await fetch('/api/session',{method:'DELETE'});if(!response.ok)throw new Error('退出登录未完成，请稍后重试。');loseAuthentication();setQuery('')}
    catch(problem){setNotice(chineseMessage(problem.message,'退出登录未完成，请重试。'))}
  }
  if (authenticated === null) return <main className="starting" aria-live="polite"><div>{connectionError || '正在连接…'}{connectionError && <button type="button" className="text-button" onClick={checkSession}>重新连接</button>}</div></main>
  if (!authenticated) return <Login onLogin={session => { setAuthenticated(true); setIsAdmin(session?.is_admin !== false) }} />
  const hasResult = !!result || loading
  const interpretation = terms(result?.plan)
  return <div className={`app ${hasResult||searchMode==='image' ? 'has-result' : ''}`}><WorkspaceHeader isAdmin={isAdmin} clues={cluesEnabled} map={mapEnabled} live={liveEnabled}><button className="text-button" aria-expanded={statusOpen} onClick={toggleStatus}>处理状态</button><button className="text-button" onClick={logout}>退出登录</button>{statusOpen&&<ProcessingStatus data={statusData} error={statusError} loading={statusLoading} onRefresh={refreshStatus} onClose={()=>setStatusOpen(false)}/>}</WorkspaceHeader><main className="main"><section className="search-area"><div className="intro"><div className="search-identity"><BrandMark/><span>视迹</span></div><h1>视频线索检索</h1></div><form className="search-form" onSubmit={submit} role="search"><SearchIcon/><label className="sr-only" htmlFor="query">描述需要查找的视频线索</label><input id="query" ref={searchInput} value={searchMode==='image'?(imageFile?.name||'选择目标图片'):query} readOnly={searchMode==='image'} onChange={event=>setQuery(event.target.value)} placeholder="描述你要查找的人物、车辆或场景" maxLength={1000} autoComplete="off"/>{appearanceEnabled&&<button type="button" className="image-search-trigger" aria-label="上传图片检索" title="上传图片检索" onClick={imageMode}><svg width="21" height="21" viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="3" stroke="currentColor" strokeWidth="1.7"/><circle cx="8" cy="9" r="1.5" fill="currentColor"/><path d="m4 18 5-5 3 3 4-5 5 6" stroke="currentColor" strokeWidth="1.7"/></svg></button>}{searchMode==='text'?<button type="submit" disabled={loading||!query.trim()}>{loading?'搜索中…':'搜索'}</button>:<button type="button" className="text-mode-button" onClick={textMode}>文字检索</button>}</form><input ref={imagePicker} className="sr-only" aria-label="选择检索图片" type="file" accept="image/jpeg,image/png" onChange={event=>{const file=event.target.files?.[0];if(file){setImageFile(file);setSearchMode('image')}event.target.value=''}}/>{searchMode==='text'&&<div className="search-examples" aria-label="搜索示例">{['白色轿车','穿黑色上衣的人','背包骑车人'].map(text=><button key={text} type="button" onClick={()=>chooseExample(text)} disabled={loading}>{text}</button>)}</div>}</section>
    {searchMode==='image'&&<ImageSearch embedded onChooseFile={imageMode} initialFile={imageFile} onUnauthenticated={loseAuthentication}/>}
    {loading && <FloatingNotice duration={0} dismissible={false}><div className="loading"><span className="spinner"/>正在检索画面描述与相关线索，请稍候。<button type="button" className="text-button cancel-search" onClick={cancelSearch}>取消等待</button></div></FloatingNotice>}
    {notice && <FloatingNotice>{notice}</FloatingNotice>}
    {error && <FloatingNotice className="notice error" role="alert" messageKey={error}><p>{error}</p><button className="text-button" onClick={retrySearch} disabled={loading || !lastAttempt.current}>{restartRequired?'重新理解原描述':'重试上次检索'}</button>{canUseKeywordSearch(errorCode,query,lastAttempt.current?.text)&&<><p>也可按原文匹配已分析的文本；时间、地点与否定表达会作为文字匹配。</p><button type="button" className="text-button" onClick={searchKeywords} disabled={loading}>按原文全文搜索</button></>}</FloatingNotice>}
    {searchMode==='text'&&result && <section id="search-results" className="result-section" aria-live="polite" aria-busy={loading}>{result.guidance&&<SearchGuidance key={result.guidance.context||result.request_id||result.query} result={result} loading={loading} onAdjust={adjustSearch} onRestart={chooseExample}/>} {!result.guidance&&interpretation.length > 0 && <div className="understanding"><span>搜索理解</span><div className="tags">{interpretation.map((x, i) => <span key={`${x}-${i}`}>{x}</span>)}</div></div>}{result.status === 'clarification_required' ? <SearchClarification key={result.request_id||result.query} result={result} loading={loading} onSupplement={chooseExample}/> : <><div className="result-heading"><div><p className="eyebrow">搜索结果</p><h2>找到 {result.total ?? result.results?.length ?? 0} 条候选线索</h2></div><span className="result-tools">{keywordActive&&<span className="admin-badge">全文</span>}<InfoBubble title="检索说明">{keywordActive&&<p className="coverage" role="status">已选择全文搜索：时间、地点与否定表达按文字匹配。可在下方选择点位收窄结果，或重新理解原描述。</p>}{result.retrieval?.message && <p className="coverage">{chineseMessage(result.retrieval.message,'检索服务暂时不可用，请稍后重试。')}</p>}{result.coverage?.message && <p className="coverage">{chineseMessage(result.coverage.message,'部分来源未覆盖本次查询范围。')}；没有结果不代表目标一定不存在。</p>}<p>请结合原始画面核验相关线索。</p></InfoBubble></span></div>{result.total > (result.results?.length || 0) && <p className="coverage">按相关度排序，当前显示第 {((result.pagination?.page || 1) - 1) * (result.pagination?.page_size || result.results.length) + 1}–{((result.pagination?.page || 1) - 1) * (result.pagination?.page_size || result.results.length) + result.results.length} 条。</p>}{result.results?.length ? <div className="result-list">{result.results.map((item, i) => <ResultCard item={item} key={item.observation_id || i} onInspect={setSelected} onPlay={setPlayerItem}/>)}</div> : <div className="empty"><SearchIcon size={30}/><h2 className="help-heading">暂时没有找到匹配线索<InfoBubble title="查询调整建议"><p>{result.plan?.start || result.plan?.end ? '时间条件只匹配已核实拍摄时间的记录。可以移除上方时间条件后继续查找。' : '可以移除上方一项条件，或选择其他点位后重新检索。没有找到线索不代表画面中一定没有该目标。'}</p></InfoBubble></h2></div>}{result.pagination && result.total > 0 && <nav className="search-pagination" aria-label="搜索结果分页"><button type="button" disabled={loading || result.pagination.page === 1} onClick={() => changePage(result.pagination.first_cursor)}>首页</button><button type="button" disabled={loading || !result.pagination.previous_cursor} onClick={() => changePage(result.pagination.previous_cursor)}>上一页</button><span>第 {result.pagination.page} / {result.pagination.total_pages} 页 · 每页 {result.pagination.page_size} 条</span><button type="button" disabled={loading || !result.pagination.next_cursor} onClick={() => changePage(result.pagination.next_cursor)}>下一页</button></nav>}</>}</section>}</main>{!hasResult&&searchMode==='text'&&<footer className="workspace-footer"><span>中文检索</span>{appearanceEnabled&&<span>图片检索</span>}<span>视频线索工作台</span></footer>}{selected && <EvidenceDialog key={selected.observation_id} onNavigate={setSelected} item={selected} onClose={() => setSelected(null)} onPlay={detail => { setSelected(null); setPlayerItem(detail) }} onUnauthenticated={loseAuthentication}/>}{playerItem && <VideoDialog item={playerItem} onClose={() => setPlayerItem(null)}/>}</div>
}

export default App
