import WorkspaceHeader from './WorkspaceHeader.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {Login} from './App.jsx'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {chineseMessage} from './chinese-copy.js'
import {liveSource,liveConfiguration,bufferedPlaybackTarget} from './live-playback.js'

function LivePlayer({camera,quality,onUnauthenticated}){
  const video=useRef(null),[attempt,setAttempt]=useState(0),[error,setError]=useState(''),[status,setStatus]=useState('正在连接…')
  useEffect(()=>{
    const element=video.current
    let hls,disposed=false,reconnect,networkRetries=0,mediaRetries=0
    setError('');setStatus('正在连接…')
    const url=liveSource(camera.camera_id,quality)
    async function start(){
      const {default:Hls}=await import('hls.js')
      if(disposed)return
      if(Hls.isSupported()){
        hls=new Hls(liveConfiguration(quality))
        hls.on(Hls.Events.ERROR,(_event,problem)=>{
          if(disposed)return
          if(problem.response?.code===401){onUnauthenticated();hls.stopLoad();return}
          if(!problem.fatal)return
          if(problem.type===Hls.ErrorTypes.NETWORK_ERROR&&networkRetries<3){
            clearTimeout(reconnect);networkRetries++;setStatus('正在重新连接…')
            reconnect=setTimeout(()=>{if(!disposed)hls.startLoad(-1)},Math.min(5000,networkRetries*1500));return
          }
          if(problem.type===Hls.ErrorTypes.MEDIA_ERROR&&mediaRetries++<1){hls.recoverMediaError();return}
          setError('监控连接中断，请重新连接。');setStatus('连接中断');hls.stopLoad()
        })
        hls.on(Hls.Events.FRAG_BUFFERED,()=>{
          if(disposed)return
          networkRetries=0
          const ranges=Array.from({length:element.buffered.length},(_,n)=>[element.buffered.start(n),element.buffered.end(n)])
          const target=bufferedPlaybackTarget(ranges,element.currentTime,hls.liveSyncPosition)
          if(target!==null)element.currentTime=target
          element.play().catch(problem=>{if(!disposed&&problem.name==='NotAllowedError')setStatus('请点击播放按钮')})
        })
        hls.loadSource(url);hls.attachMedia(element)
      }else if(element.canPlayType('application/vnd.apple.mpegurl'))element.src=url
      else throw new Error('当前浏览器不支持播放该监控，请使用新版浏览器。')
    }
    start().catch(problem=>{if(!disposed&&problem.name!=='AbortError'){setError(chineseMessage(problem.message));setStatus('连接失败')}})
    return()=>{disposed=true;clearTimeout(reconnect);hls?.destroy();element.pause();element.removeAttribute('src');element.load()}
  },[camera.camera_id,quality,attempt])
  return <section className="live-player"><div className="admin-card-heading"><div><h2>{camera.display_name}</h2><span role="status">{status}</span></div><button className="text-button" type="button" onClick={()=>setAttempt(value=>value+1)}>重新连接</button></div><video ref={video} controls autoPlay muted playsInline onPlaying={()=>{setStatus('正在播放');setError('')}} onWaiting={()=>setStatus('正在缓冲…')} onError={()=>{setStatus('播放中断');setError('画面无法播放，请重新连接。')}}/>{error&&<FloatingNotice role="alert" className="notice error">{error}</FloatingNotice>}</section>
}

export default function LiveMonitoring(){
  const [authenticated,setAuthenticated]=useState(null),[cameras,setCameras]=useState([]),[selected,setSelected]=useState(()=>new URLSearchParams(location.search).get('camera')||''),[error,setError]=useState(''),[attempt,setAttempt]=useState(0)
  const [quality,setQuality]=useState('preview'),[layout,setLayout]=useState('single'),[gridIds,setGridIds]=useState([]),[isAdmin,setIsAdmin]=useState(false)
  function unauthenticated(){setAuthenticated(false);setCameras([]);setSelected('')}
  useEffect(()=>{
    const controller=new AbortController();setError('')
    async function load(){
      const session=await fetch('/api/session',{signal:controller.signal}).then(response=>response.json())
      if(!session.authenticated){unauthenticated();return}setAuthenticated(true);setIsAdmin(session.is_admin===true)
      const response=await fetch('/api/v1/live/cameras',{signal:controller.signal,cache:'no-store'})
      if(response.status===401){unauthenticated();return}
      if(!response.ok)throw new Error(response.status===404?'实时监控尚未启用。':'监控目录读取失败，请重试。')
      const value=await response.json()
      if(!Array.isArray(value.items)||value.items.length>128||value.items.some(row=>typeof row.camera_id!=='string'||typeof row.display_name!=='string'))throw new Error('监控目录无效，请重试。')
      if(controller.signal.aborted)return
      setCameras(value.items);setGridIds(current=>{const valid=current.filter(id=>value.items.some(camera=>camera.camera_id===id));return valid.length?valid:value.items.slice(0,4).map(camera=>camera.camera_id)});setSelected(current=>value.items.some(row=>row.camera_id===current)?current:value.items[0]?.camera_id||'')
    }
    load().catch(problem=>{if(problem.name!=='AbortError')setError(chineseMessage(problem.message))})
    return()=>controller.abort()
  },[attempt,authenticated===false])
  if(authenticated===false)return <Login onLogin={()=>{setAuthenticated(true);setAttempt(value=>value+1)}}/>
  const camera=cameras.find(row=>row.camera_id===selected)
  const displayed=layout==='grid'?cameras.filter(row=>gridIds.includes(row.camera_id)):camera?[camera]:[]
  function choose(cameraId){
    if(layout==='single'){setSelected(cameraId);return}
    setGridIds(current=>current.includes(cameraId)?current.filter(id=>id!==cameraId):current.length<4?[...current,cameraId]:current)
  }
  return <div className="app"><WorkspaceHeader active="live" isAdmin={isAdmin}/><main className="live-main"><div className="admin-section-title"><div className="live-title"><h1>实时监控</h1><span className="admin-muted">已选择 {displayed.length} 个点位</span><InfoBubble title="观看说明">选择点位观看实时画面。切换点位或离开页面会停止原播放，四画面最多同时观看四个点位。</InfoBubble></div><div className="segmented-control" aria-label="画面布局"><button type="button" aria-pressed={layout==='single'} onClick={()=>setLayout('single')}>单画面</button><button type="button" aria-pressed={layout==='grid'} onClick={()=>setLayout('grid')}>四画面</button></div><button className="admin-button secondary" type="button" onClick={()=>setAttempt(value=>value+1)}>刷新点位</button></div>{error&&<FloatingNotice role="alert" className="notice error">{error}</FloatingNotice>}{authenticated===null&&!error&&<p role="status">正在验证登录…</p>}<div className="live-workspace"><aside className="admin-card live-camera-list"><h2>点位选择</h2>{cameras.map(row=><label key={row.camera_id} className={displayed.some(value=>value.camera_id===row.camera_id)?'is-selected':''}><input type={layout==='grid'?'checkbox':'radio'} name="live-camera" checked={displayed.some(value=>value.camera_id===row.camera_id)} disabled={layout==='grid'&&gridIds.length>=4&&!gridIds.includes(row.camera_id)} onChange={()=>choose(row.camera_id)}/><span>{row.display_name}</span></label>)}</aside><section className="live-stage" aria-label="监控画面"><div className={layout==='grid'?'live-grid':'live-single'}>{displayed.map(row=><LivePlayer key={row.camera_id} camera={row} quality={row.preview_available?quality:'hls'} onUnauthenticated={unauthenticated}/>)}</div>{!displayed.length&&authenticated&&!error&&<div className="admin-empty">请选择可观看的监控点位。</div>}<div className="live-quality"><label>观看画质<select value={quality} onChange={event=>setQuality(event.target.value)}><option value="preview">流畅预览</option><option value="hls">原画</option></select></label><InfoBubble title="画质说明">流畅预览使用已有的低码率通道；未配置预览的点位使用原画。播放状态以各画面实时返回为准。</InfoBubble></div></section></div></main></div>
}
