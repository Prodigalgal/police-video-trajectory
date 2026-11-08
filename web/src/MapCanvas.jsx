import React,{useEffect,useRef,useState} from 'react'
import {mapDocumentView,validMapReply} from './maps/map-document.js'

function MapDocument({points,links,onSelect,onPick,onReload}){
  const frame=useRef(null),content=useRef({points,links}),callbacks=useRef({onSelect,onPick}),ready=useRef(false)
  const [nonce]=useState(()=>crypto.randomUUID()),[error,setError]=useState(''),[loading,setLoading]=useState(true)
  content.current={points,links};callbacks.current={onSelect,onPick}
  function send(kind){frame.current?.contentWindow?.postMessage({schema:'map-document-v1',nonce,kind,...(kind==='view'?mapDocumentView(content.current.points,content.current.links,Boolean(callbacks.current.onPick)):{})},location.origin)}
  useEffect(()=>{
    const timer=setTimeout(()=>{setLoading(false);setError('地图加载超时，可重新加载。')},25000)
    function receive(event){
      if(event.origin!==location.origin||event.source!==frame.current?.contentWindow||!validMapReply(event.data,nonce))return
      const value=event.data
      if(value.kind==='ready'){ready.current=true;send('view')}
      else if(value.kind==='loaded'){clearTimeout(timer);setLoading(false);setError('')}
      else if(value.kind==='error'){clearTimeout(timer);setLoading(false);setError(value.code==='configuration'?'地图服务尚未配置，请联系管理员。':'地图资源暂不可用，可重新加载。')}
      else if(value.kind==='select'){
        const point=content.current.points.find(point=>(point.observation_id||point.camera_id)===value.id)
        if(point)callbacks.current.onSelect?.(point)
      }else if(value.kind==='pick')callbacks.current.onPick?.({longitude:value.longitude,latitude:value.latitude})
    }
    window.addEventListener('message',receive)
    return()=>{clearTimeout(timer);window.removeEventListener('message',receive);ready.current=false}
  },[nonce])
  useEffect(()=>{if(ready.current)send('view')},[points,links,Boolean(onPick)])
  return <><iframe ref={frame} className="map-canvas map-document" src="/_maps/frame" title="高德点位地图" onLoad={()=>send('hello')} referrerPolicy="same-origin"/>{loading&&<p className="map-feedback" role="status">正在加载高德地图…</p>}{error&&<div className="map-feedback notice error" role="alert"><p>{error}</p><button type="button" className="text-button" onClick={onReload}>重新加载地图</button></div>}</>
}

export default function MapCanvas(props){
  const [revision,setRevision]=useState(0)
  return <div className="map-frame"><MapDocument key={revision} points={props.points} links={props.links||[]} onSelect={props.onSelect} onPick={props.onPick} onReload={()=>setRevision(value=>value+1)}/></div>
}
