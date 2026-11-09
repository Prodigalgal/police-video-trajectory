import React,{useEffect,useState} from 'react'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {inputApi,previewFrame} from './analysis-input-client.js'
import {validateNearbyRecords} from './nearby-records.js'
import {formatTime} from './result-format.js'
import {chineseMessage} from './chinese-copy.js'

export default function NearbyRecordsPanel({detail,refresh,onUnauthenticated,onNavigate}){
  const [data,setData]=useState(null),[error,setError]=useState(''),[selected,setSelected]=useState(null),[preview,setPreview]=useState(null),[reading,setReading]=useState(false)
  useEffect(()=>{
    const controller=new AbortController();setData(null);setError('');setSelected(null)
    inputApi(`observations/${detail.observation_id}/nearby-records?expected_version=${detail.record_version}&limit=9`,{signal:controller.signal})
      .then(value=>{const checked=validateNearbyRecords(value,detail);if(!controller.signal.aborted)setData(checked)})
      .catch(problem=>{if(!controller.signal.aborted){setError(chineseMessage(problem.message));if(problem.unauthorized)onUnauthenticated()}})
    return()=>controller.abort()
  },[detail.observation_id,detail.record_version,refresh])
  useEffect(()=>{
    const controller=new AbortController();let object;setPreview(null);setReading(false)
    if(selected){setReading(true);previewFrame(selected,controller.signal).then(blob=>{if(!controller.signal.aborted){object=URL.createObjectURL(blob);setPreview(object)}}).catch(problem=>{if(!controller.signal.aborted){setError(chineseMessage(problem.message));if(problem.unauthorized)onUnauthenticated()}}).finally(()=>{if(!controller.signal.aborted)setReading(false)})}
    return()=>{controller.abort();if(object)URL.revokeObjectURL(object)}
  },[selected])
  return <div className="nearby-records"><InfoBubble title="附近画面的时间基准">按同点位、同采集任务代次的记录时间排列。缺少源片内时钟的旧画面用于前后浏览，不组成连续视频或多帧分析序列。</InfoBubble>{error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{!data&&!error&&<p role="status">正在读取前后留存画面…</p>}{data&&<div className="nearby-grid">{data.items.map(frame=><button type="button" key={frame.frame_id} className="nearby-thumbnail" aria-pressed={selected?.frame_id===frame.frame_id} onClick={()=>{setError('');setSelected(frame)}}><img loading="lazy" decoding="async" src={`/api/v1/frames/${frame.frame_id}/image`} alt={`${frame.frame_id===detail.frame_id?'当前':'附近'}留存画面`}/><span>{frame.frame_id===detail.frame_id?'当前画面':frame.record_offset_ms<0?'之前画面':'之后画面'}</span><small>{formatTime(frame.received_at)}</small></button>)}</div>}{reading&&<p role="status">正在校验所选原图…</p>}{preview&&selected&&<figure className="context-preview"><img src={preview} alt="所选附近留存画面"/><figcaption>{formatTime(selected.received_at)}{onNavigate&&<button type="button" className="text-button" onClick={()=>onNavigate(selected)}>查看该画面线索</button>}</figcaption></figure>}</div>
}
