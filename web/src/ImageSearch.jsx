import React,{useEffect,useRef,useState} from 'react'
import {Login,VideoDialog} from './App.jsx'
import EvidenceDialog from './EvidenceDialog.jsx'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {formatOffset,formatTime,verifyEvidenceDigest} from './result-format.js'
import {appearanceApi,appearanceJson,uploadAppearance,validateAppearance,validBox} from './appearance-client.js'
import {chineseMessage} from './chinese-copy.js'

function RoiPicker({url,size,box,onChange,disabled}){
  const origin=useRef(null)
  function point(event){const bounds=event.currentTarget.getBoundingClientRect();return [Math.max(0,Math.min(size.width,Math.round((event.clientX-bounds.left)/bounds.width*size.width))),Math.max(0,Math.min(size.height,Math.round((event.clientY-bounds.top)/bounds.height*size.height)))]}
  function update(event){if(!origin.current)return;const [x,y]=point(event),[a,b]=origin.current;onChange([Math.min(a,x),Math.min(b,y),Math.max(a,x),Math.max(b,y)])}
  return <><div className="appearance-picker"><img src={url} alt="待检索的监控截图" draggable={false}/><svg viewBox={`0 0 ${size.width} ${size.height}`} aria-label="拖动框选目标，也可使用下方坐标输入" onPointerDown={event=>{if(disabled||event.button!==0)return;origin.current=point(event);event.currentTarget.setPointerCapture(event.pointerId)}} onPointerMove={event=>{if(!disabled)update(event)}} onPointerUp={event=>{if(!disabled)update(event);origin.current=null}} onPointerCancel={()=>{origin.current=null}}>{box&&<rect x={box[0]} y={box[1]} width={Math.max(0,box[2]-box[0])} height={Math.max(0,box[3]-box[1])} className="appearance-roi" vectorEffect="non-scaling-stroke"/>}</svg></div><div className="appearance-roi-fields">{['左边界','上边界','右边界','下边界'].map((label,index)=><label key={label}>{label}<input type="number" min="0" max={index%2?size.height:size.width} value={box?.[index]??''} disabled={disabled} onChange={event=>{const value=event.target.value;if(value===''){onChange(null);return}const next=box?[...box]:[0,0,size.width,size.height];next[index]=Number(value);onChange(next)}}/></label>)}</div><button className="text-button" type="button" disabled={disabled} onClick={()=>onChange([0,0,size.width,size.height])}>图片已裁为单个目标，使用整张图片</button></>
}

function Period({item}){
  if(item.period.clock==='verified_source')return <span>{formatTime(item.period_start)} 至 {formatTime(item.period_end)}</span>
  if(item.period.clock==='relative_media')return <span>片内 {formatOffset(item.period.start_ms)} 至 {formatOffset(item.period.end_ms)}</span>
  return <span>时间位置未知</span>
}

function CandidateImage({item,onInspect}){
  const [url,setUrl]=useState(null),[verified,setVerified]=useState(false)
  useEffect(()=>{const controller=new AbortController();let object;setUrl(null);setVerified(false)
    async function load(){const response=await fetch(`/api/v1/frames/${item.frame_id}/image`,{cache:'no-store',signal:controller.signal});if(!response.ok)return;const bytes=await response.arrayBuffer();if(bytes.byteLength>8*1024*1024)return;const digest=await verifyEvidenceDigest(bytes,item.image_sha256);if(controller.signal.aborted||digest==='mismatch')return;object=URL.createObjectURL(new Blob([bytes],{type:'image/jpeg'}));setUrl(object);setVerified(digest==='valid')}
    load().catch(()=>{/* The detail action remains available when the thumbnail cannot load. */});return()=>{controller.abort();if(object)URL.revokeObjectURL(object)}
  },[item.frame_id,item.image_sha256])
  return <button type="button" className="appearance-result-image" onClick={onInspect} aria-label={`查看${item.camera_id}的相似目标详情`}>{url?<img src={url} alt="相似候选原帧"/>:<span>打开详情查看原图</span>}{verified&&<svg viewBox={`0 0 ${item.image_width} ${item.image_height}`} aria-hidden="true"><rect x={item.bbox_xyxy[0]} y={item.bbox_xyxy[1]} width={item.bbox_xyxy[2]-item.bbox_xyxy[0]} height={item.bbox_xyxy[3]-item.bbox_xyxy[1]} className="appearance-roi" vectorEffect="non-scaling-stroke"/></svg>}</button>
}

export default function ImageSearch(){
  const [authenticated,setAuthenticated]=useState(null),[capabilities,setCapabilities]=useState(null),[error,setError]=useState(''),[startup,setStartup]=useState(0)
  const [file,setFile]=useState(null),[url,setUrl]=useState(null),[size,setSize]=useState(null),[box,setBox]=useState(null),[type,setType]=useState('vehicle'),[camera,setCamera]=useState(''),[start,setStart]=useState(''),[end,setEnd]=useState('')
  const [busy,setBusy]=useState(false),[result,setResult]=useState(null),[history,setHistory]=useState([0]),[page,setPage]=useState(0),[selected,setSelected]=useState(null),[player,setPlayer]=useState(null)
  const active=useRef(null),snapshot=useRef(null),fileGeneration=useRef(0),preview=useRef(null),mounted=useRef(false)
  function closeSnapshot(value){if(value?.id)fetch('/api/v1/appearance/queries/'+value.id,{method:'DELETE',keepalive:true}).catch(()=>{/* Snapshot TTL bounds cleanup when disconnected. */})}
  function reset(){active.current?.abort();active.current=null;setBusy(false);closeSnapshot(snapshot.current);snapshot.current=null;setResult(null);setHistory([0]);setPage(0);setSelected(null);setPlayer(null)}
  function unauthenticated(){fileGeneration.current+=1;reset();setAuthenticated(false);setCapabilities(null);setFile(null);setUrl(null);setSize(null);setBox(null);if(preview.current){URL.revokeObjectURL(preview.current);preview.current=null}}
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;fileGeneration.current+=1;active.current?.abort();closeSnapshot(snapshot.current);if(preview.current)URL.revokeObjectURL(preview.current)}},[])
  useEffect(()=>{const controller=new AbortController();fetch('/api/session',{signal:controller.signal}).then(appearanceJson).then(value=>{if(!controller.signal.aborted)setAuthenticated(value.authenticated)}).catch(problem=>{if(!controller.signal.aborted)setError(chineseMessage(problem.message))});return()=>controller.abort()},[startup])
  useEffect(()=>{if(!authenticated)return;const controller=new AbortController();setCapabilities(null);appearanceApi('/api/v1/appearance/capabilities',{signal:controller.signal}).then(value=>{if(!controller.signal.aborted)setCapabilities(value)}).catch(problem=>{if(!controller.signal.aborted){setError(chineseMessage(problem.message));if(problem.unauthorized)unauthenticated()}});return()=>controller.abort()},[authenticated,startup])
  async function chooseFile(event){
    const candidate=event.target.files?.[0];if(!candidate)return;const generation=++fileGeneration.current;reset();setError('');setFile(null);setSize(null);setBox(null);setUrl(null)
    if(preview.current){URL.revokeObjectURL(preview.current);preview.current=null}
    if(!['image/jpeg','image/png'].includes(candidate.type)||candidate.size>8*1024*1024||!candidate.size){setError('请选择不超过 8 MB 的 JPEG 或 PNG 图像。');event.target.value='';return}
    const object=URL.createObjectURL(candidate);preview.current=object;const image=new Image()
    try{await new Promise((resolve,reject)=>{image.onload=resolve;image.onerror=reject;image.src=object});if(generation!==fileGeneration.current||!mounted.current)return
      if(image.naturalWidth>20000||image.naturalHeight>20000||image.naturalWidth*image.naturalHeight>16777216)throw new Error('图片尺寸过大，请先裁剪目标区域。')
      setFile(candidate);setUrl(object);setSize({width:image.naturalWidth,height:image.naturalHeight})
    }catch(problem){if(generation===fileGeneration.current&&mounted.current){setError(chineseMessage(problem.message,'图片无法解码，请重新选择。'));URL.revokeObjectURL(object);preview.current=null}}
  }
  async function search(event){
    event.preventDefault();if(!file||!size||!validBox(box,size.width,size.height)||!capabilities)return
    reset();setError('');const controller=new AbortController();active.current=controller;setBusy(true)
    const expected={id:crypto.randomUUID(),profile:capabilities.profile_sha256,type,image:null}
    try{
      const bytes=await file.arrayBuffer();expected.image=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),n=>n.toString(16).padStart(2,'0')).join('')
      if(start&&end&&new Date(start)>new Date(end))throw new Error('开始时间不能晚于结束时间。')
      const metadata={request_id:expected.id,media_type:file.type,bbox_xyxy:box,target_type:type,profile:capabilities.profile.profile,profile_sha256:expected.profile,cameras:camera?[camera]:[],start:start?new Date(start).toISOString():null,end:end?new Date(end).toISOString():null}
      const form=new FormData();form.append('metadata',JSON.stringify(metadata));form.append('file',file)
      const value=validateAppearance(await uploadAppearance(form,controller.signal),expected)
      if(active.current!==controller){closeSnapshot(expected);return}snapshot.current=expected;setResult(value);setPage(0);setHistory([0])
    }catch(problem){closeSnapshot(expected);if(active.current===controller&&problem.name!=='AbortError'){setError(chineseMessage(problem.message));if(problem.unauthorized)unauthenticated()}}
    finally{if(active.current===controller){active.current=null;setBusy(false)}}
  }
  async function changePage(target,offset){
    if(busy||!snapshot.current)return;const expected=snapshot.current;const controller=new AbortController();active.current=controller;setBusy(true);setError('');setSelected(null);setPlayer(null)
    try{const value=validateAppearance(await appearanceApi(`/api/v1/appearance/queries/${expected.id}?offset=${offset}&limit=10`,{signal:controller.signal}),expected);if(active.current===controller){setResult(value);setPage(target);setHistory(values=>{const next=values.slice(0,target+1);next[target]=offset;return next})}}
    catch(problem){if(active.current===controller&&problem.name!=='AbortError'){setError(chineseMessage(problem.message));if(problem.unauthorized)unauthenticated()}}
    finally{if(active.current===controller){active.current=null;setBusy(false)}}
  }
  function cancel(){active.current?.abort();active.current=null;setBusy(false)}
  if(authenticated===null)return <main className="starting"><p role="status">正在连接…</p>{error&&<FloatingNotice className="error" role="alert">{error}<button className="text-button" onClick={()=>setStartup(value=>value+1)}>重新连接</button></FloatingNotice>}</main>
  if(!authenticated)return <Login onLogin={()=>{setAuthenticated(true);setError('')}}/>
  return <div className="app has-result"><header className="topbar"><a className="brand" href="/">视迹<small>以图搜图</small></a><nav className="header-tools" aria-label="主导航"><a className="text-button" href="/">自然语言搜索</a><a className="text-button" href="/clues">线索工作区</a><InfoBubble title="以图搜图说明">选择单个人物或车辆，检索已入库有效帧中的相似外观。分值仅用于候选排序，仍需核验原图；相邻帧的局部轨迹用于整理候选时段，不代表确认身份。新增原始录像不会直接参与本搜索。</InfoBubble></nav></header><main className="appearance-main"><div className="admin-section-title"><div><p className="eyebrow">从目标画面继续查找</p><h1>以图搜图</h1></div><button className="text-button" disabled={busy} onClick={()=>{setError('');setStartup(value=>value+1)}}>刷新索引状态</button></div>{error&&<FloatingNotice className="error" role="alert" onDismiss={()=>setError('')}>{error}</FloatingNotice>}<div className="appearance-layout"><form className="admin-card appearance-form" onSubmit={search}><fieldset disabled={busy||!capabilities}><legend>选择目标</legend><label htmlFor="appearance-file">监控截图<input id="appearance-file" type="file" accept="image/jpeg,image/png" onChange={chooseFile}/></label>{url&&size?<RoiPicker url={url} size={size} box={box} onChange={value=>{if(snapshot.current)reset();setBox(value)}} disabled={busy}/>:<div className="appearance-upload-empty">上传图片，再框选一个目标</div>}<label htmlFor="appearance-type">目标类型<select id="appearance-type" value={type} onChange={event=>{reset();setType(event.target.value)}}><option value="vehicle">车辆</option><option value="person">人物</option></select></label><label htmlFor="appearance-camera">点位范围<select id="appearance-camera" value={camera} onChange={event=>{reset();setCamera(event.target.value)}}><option value="">所有可查点位</option>{capabilities?.cameras?.map(value=><option key={value.camera_id} value={value.camera_id}>{value.display_name}</option>)}</select></label><details><summary>限定来源时间</summary><label htmlFor="appearance-start">开始时间<input id="appearance-start" type="datetime-local" value={start} onChange={event=>{reset();setStart(event.target.value)}}/></label><label htmlFor="appearance-end">结束时间<input id="appearance-end" type="datetime-local" value={end} onChange={event=>{reset();setEnd(event.target.value)}}/></label><InfoBubble title="时间筛选范围">时间条件仅匹配已核实的来源时间；没有来源时间的画面可通过不限定时间的搜索查找，并按已登记片内位置展示。</InfoBubble></details><div className="appearance-form-actions"><button className="primary" type="submit" disabled={!file||!size||!validBox(box,size.width,size.height)}>{busy?'正在搜索…':'查找相似目标'}</button></div></fieldset>{busy&&<div className="appearance-progress" role="status">正在检索已入库目标…<button type="button" className="text-button" onClick={cancel}>停止等待</button></div>}{capabilities&&<div className="appearance-index-status"><span>当前可查目标 {capabilities.counts?.completed||0}</span><InfoBubble title="索引状态">已入库且已生成外观特征的可靠目标区域参与搜索。等待 {capabilities.counts?.queued||0}，处理 {capabilities.counts?.active||0}，失败 {capabilities.counts?.failed||0}。模型 {capabilities.profile?.profile}。管理员可在后台扫描已入库目标和控制索引消费。</InfoBubble></div>}</form><section className="appearance-results" aria-label="相似候选时段" aria-busy={busy}>{result?<><div className="admin-card-heading"><h2>相似候选时段</h2><span>第 {page+1} 页</span><InfoBubble title="候选排序说明">候选数 {result.total_candidate_periods}，参与比较的目标 {result.summary.scanned}。{result.summary.truncated?'只保留最高分的前 1000 个目标再整理时段，不能视为完整轨迹。':'时段只连接同一会话和局部轨迹、间隔不超过 5.5 秒的已见画面。'}{result.removed_stale_sightings>0?'部分已更新或删除画面已从本页移除。':''}</InfoBubble></div>{result.items.map(item=><article className="admin-card appearance-result" key={item.observation_id}><CandidateImage item={item} onInspect={()=>setSelected(item)}/><div><h3>{capabilities?.cameras?.find(value=>value.camera_id===item.camera_id)?.display_name||item.camera_id}</h3><p><Period item={item}/></p><p>外观分值 {item.similarity.toFixed(3)} · {item.sightings} 张候选画面</p><button className="play-button" type="button" onClick={()=>setSelected(item)}>查看详情和原始画面</button></div></article>)}{!result.items.length&&<div className="admin-card admin-empty">当前索引范围内没有可返回的候选，请检查点位、时间或目标类型。</div>}<div className="admin-list-paging"><button className="admin-button secondary" disabled={busy||page===0} onClick={()=>changePage(page-1,history[page-1])}>上一页</button><span>第 {page+1} 页</span><button className="admin-button secondary" disabled={busy||!result.has_more} onClick={()=>changePage(page+1,result.next_offset)}>下一页</button></div></>:<div className="admin-card appearance-results-empty">{capabilities?'上传截图并选择目标后，候选时段会显示在这里。':'正在读取以图搜图配置…'}</div>}</section></div></main>{selected&&!player&&<EvidenceDialog key={selected.observation_id} item={selected} onClose={()=>setSelected(null)} onPlay={item=>{setSelected(null);setPlayer(item)}} onUnauthenticated={unauthenticated} onNavigate={setSelected}/>} {player&&<VideoDialog item={player} onClose={()=>setPlayer(null)}/>}</div>
}
