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
  const [quality,setQuality]=useState('preview')
  function unauthenticated(){setAuthenticated(false);setCameras([]);setSelected('')}
  useEffect(()=>{
    const controller=new AbortController();setError('')
    async function load(){
      const session=await fetch('/api/session',{signal:controller.signal}).then(response=>response.json())
      if(!session.authenticated){unauthenticated();return}setAuthenticated(true)
      const response=await fetch('/api/v1/live/cameras',{signal:controller.signal,cache:'no-store'})
      if(response.status===401){unauthenticated();return}
      if(!response.ok)throw new Error(response.status===404?'实时监控尚未启用。':'监控目录读取失败，请重试。')
      const value=await response.json()
      if(!Array.isArray(value.items)||value.items.length>128||value.items.some(row=>typeof row.camera_id!=='string'||typeof row.display_name!=='string'))throw new Error('监控目录无效，请重试。')
      if(controller.signal.aborted)return
      setCameras(value.items);setSelected(current=>value.items.some(row=>row.camera_id===current)?current:value.items[0]?.camera_id||'')
    }
    load().catch(problem=>{if(problem.name!=='AbortError')setError(chineseMessage(problem.message))})
    return()=>controller.abort()
  },[attempt,authenticated===false])
  if(authenticated===false)return <Login onLogin={()=>{setAuthenticated(true);setAttempt(value=>value+1)}}/>
  const camera=cameras.find(row=>row.camera_id===selected)
  return <div className="app"><header className="topbar"><a className="text-button" href="/">返回检索</a><div className="header-tools"><h1>实时监控</h1><InfoBubble title="观看说明">选择点位观看实时画面。默认使用低码率流畅预览，原画需要更高带宽。切换点位或离开页面会停止当前播放。</InfoBubble></div></header><main className="live-main"><div className="admin-section-title"><label>监控点位<select value={selected} onChange={event=>setSelected(event.target.value)} disabled={!cameras.length}>{cameras.map(row=><option key={row.camera_id} value={row.camera_id}>{row.display_name}</option>)}</select></label><label>观看画质<select value={camera?.preview_available?quality:'hls'} disabled={!camera?.preview_available} onChange={event=>setQuality(event.target.value)}><option value="preview">流畅预览</option><option value="hls">原画</option></select></label><button className="text-button" type="button" onClick={()=>setAttempt(value=>value+1)}>刷新点位</button></div>{error&&<FloatingNotice role="alert" className="notice error">{error}</FloatingNotice>}{authenticated===null&&!error&&<p role="status">正在验证登录…</p>}{camera?<LivePlayer key={camera.camera_id} camera={camera} quality={camera.preview_available?quality:'hls'} onUnauthenticated={unauthenticated}/>:authenticated&&!error&&<p>暂无可观看的监控点位。</p>}</main></div>
}
