import React,{useEffect,useRef,useState} from 'react'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {releaseDetachedVideo} from './media-playback.js'
import {boundedVideoTime,contextCoverage,contextSeconds,videoWindow} from './video-window.js'

export default function ObservationClipPlayer({item,recordingReview=false,onExpand}){
  const media=useRef(null),[attempt,setAttempt]=useState(0),[duration,setDuration]=useState(null),[seconds,setSeconds]=useState(contextSeconds.includes(item.video_context_seconds)?item.video_context_seconds:5)
  const [mode,setMode]=useState(item.video_context_mode==='full'?'full':'window'),[position,setPosition]=useState(0),[playing,setPlaying]=useState(false),[loading,setLoading]=useState(true),[error,setError]=useState('')
  const window=videoWindow(item,duration,seconds,mode)
  const coverage=contextCoverage(window,seconds)
  const start=window?.start,end=window?.end
  const source=recordingReview?`/api/admin/recording-review/frames/${encodeURIComponent(item.frame_id)}/video`:`/api/v1/frames/${encodeURIComponent(item.frame_id)}/archive`
  useEffect(()=>{const element=media.current;return()=>releaseDetachedVideo(element)},[attempt,item.frame_id,recordingReview])
  useEffect(()=>{
    const element=media.current;if(!element)return
    const observer=typeof IntersectionObserver==='undefined'?null:new IntersectionObserver(entries=>{if(entries.some(entry=>!entry.isIntersecting))element.pause()})
    observer?.observe(element)
    const hidden=()=>{if(document.hidden)element.pause()};document.addEventListener('visibilitychange',hidden)
    return()=>{observer?.disconnect();document.removeEventListener('visibilitychange',hidden)}
  },[attempt,item.frame_id])
  useEffect(()=>{
    const element=media.current;if(!element||!window)return
    element.pause();element.currentTime=window.start;setPosition(window.start)
  },[start,end,attempt])
  function failure(){setLoading(false);setPlaying(false);setError('录像暂时无法加载，请重新加载。')}
  async function play(restart=false){
    const element=media.current;if(!element||!window)return
    if(restart||element.currentTime<window.start||element.currentTime>=window.end-.08)element.currentTime=window.start
    try{await element.play();setError('')}catch{setError('播放未能开始，请点击播放按钮重试。')}
  }
  function seek(value){const element=media.current;if(element&&window){element.currentTime=boundedVideoTime(value,window);setPosition(element.currentTime)}}
  function update(event){
    const element=event.currentTarget;if(!window)return
    if(element.currentTime>=window.end){element.pause();if(Math.abs(element.currentTime-window.end)>.02)element.currentTime=window.end}
    setPosition(boundedVideoTime(element.currentTime,window))
  }
  function reload(){setError('');setDuration(null);setLoading(true);setPlaying(false);setAttempt(value=>value+1)}
  return <section className="observation-clip" aria-label="有效帧前后视频" aria-busy={loading}>
    <div className="context-heading"><h3>画面前后视频</h3><InfoBubble title="回看范围">默认回看有效帧前后各五秒，范围以已绑定原录像为准，到边界自动停止。可定位当前有效帧，或切换完整片段。关键帧核对与高级分析位于下方辅助工具。</InfoBubble></div>
    <div className="clip-screen"><video key={attempt} ref={media} preload="metadata" playsInline muted controls src={source} aria-label="连续录像回看"
      onLoadedMetadata={event=>{const length=event.currentTarget.duration;if(!Number.isFinite(length)||length<=0){failure();return}setDuration(length);setLoading(false);setError('')}}
      onError={failure} onWaiting={()=>setLoading(true)} onCanPlay={()=>setLoading(false)} onPlaying={()=>{setPlaying(true);setLoading(false)}} onPause={()=>setPlaying(false)}
      onTimeUpdate={update} onSeeking={event=>{if(window){const value=boundedVideoTime(event.currentTarget.currentTime,window);if(Math.abs(value-event.currentTarget.currentTime)>.02)event.currentTarget.currentTime=value}}}
      onPlay={event=>{if(window&&(event.currentTarget.currentTime<window.start||event.currentTarget.currentTime>=window.end-.08))event.currentTarget.currentTime=window.start}}/>
      {loading&&!error&&<span className="clip-loading" role="status">正在加载录像…</span>}
    </div>
    <div className="clip-controls"><div className="clip-actions"><button type="button" className="admin-button" disabled={!window||Boolean(error)} onClick={()=>playing?media.current?.pause():play()}>{playing?'暂停':'播放'}</button><button type="button" className="admin-button secondary" disabled={!window||Boolean(error)} onClick={()=>play(true)}>重播片段</button><button type="button" className="admin-button secondary" disabled={window?.anchor==null||Boolean(error)} onClick={()=>{media.current?.pause();seek(window.anchor)}}>定位有效帧</button>{onExpand&&<button type="button" className="text-button" onClick={()=>{media.current?.pause();onExpand({video_context_mode:mode,video_context_seconds:seconds})}}>放大回看</button>}</div>
      <div className="clip-options"><label>回看范围 <select value={seconds} disabled={mode==='full'} onChange={event=>setSeconds(Number(event.target.value))}>{contextSeconds.map(value=><option value={value} key={value}>前后各 {value} 秒</option>)}</select></label><button type="button" className="text-button" aria-pressed={mode==='full'} onClick={()=>setMode(value=>value==='full'?'window':'full')}>{mode==='full'?'返回前后片段':'查看完整片段'}</button></div>
      {window&&<div className="clip-timeline"><label><span>片段进度</span><input type="range" aria-label="片段播放进度" min={window.start} max={window.end} step="0.05" value={boundedVideoTime(position,window)} onChange={event=>seek(Number(event.target.value))}/></label><span>{Math.max(0,position-window.start).toFixed(1)} / {(window.end-window.start).toFixed(1)} 秒</span></div>}
      {window?.anchor!=null&&<div className="clip-anchor">有效帧位于本次回看第 {(window.anchor-window.start).toFixed(1)} 秒{coverage&&<InfoBubble title={coverage.complete?'前后视频覆盖完整':'前后视频覆盖到达边界'}>本次可回看有效帧前 {coverage.before.toFixed(1)} 秒、后 {coverage.after.toFixed(1)} 秒。{coverage.complete?'已覆盖选择的前后范围。':'当前片段到达边界，不能补出未保留的画面；可缩小范围或查看完整片段。'}</InfoBubble>}</div>}
      {window?.reason&&<InfoBubble title="录像定位说明">{window.reason==='position_outside_recording'?'有效帧位置超出当前录像范围，请重新打开详情核验。':'当前帧尚无精确片内位置，可浏览已关联完整录像。'}</InfoBubble>}
    </div>
    {error&&<FloatingNotice className="notice error" role="alert">{error}<button type="button" className="text-button" onClick={reload}>重新加载</button></FloatingNotice>}
  </section>
}
