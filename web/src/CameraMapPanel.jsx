import React,{useEffect,useState} from 'react'
import MapCanvas from './MapCanvas.jsx'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {businessApi} from './investigation-client.js'
import {chineseMessage} from './chinese-copy.js'

export default function CameraMapPanel({onUnauthenticated}){
  const [cameras,setCameras]=useState(null),[selected,setSelected]=useState(''),[error,setError]=useState(''),[attempt,setAttempt]=useState(0),[query,setQuery]=useState('')
  useEffect(()=>{
    const controller=new AbortController();setError('')
    businessApi('/api/v1/investigation/cameras',{signal:controller.signal}).then(value=>{
      if(!Array.isArray(value.items)||value.items.length>200)throw new Error('点位地图资料无效，请重新读取。')
      if(!controller.signal.aborted)setCameras(value.items)
    }).catch(problem=>{if(!controller.signal.aborted){setError(chineseMessage(problem.message));if(problem.unauthorized)onUnauthenticated()}})
    return()=>controller.abort()
  },[attempt])
  const points=(cameras||[]).filter(camera=>Number.isFinite(camera.longitude)&&Number.isFinite(camera.latitude)).map(camera=>({...camera,camera_name:camera.display_name}))
  const camera=cameras?.find(value=>value.camera_id===selected)
  const filtered=(cameras||[]).filter(value=>(value.display_name+' '+(value.address||'')).includes(query.trim()))
  return <section className="camera-map-panel" aria-label="监控点位地图">{error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{!cameras&&!error&&<p role="status">正在读取监控点位…</p>}{cameras&&<div className="camera-map-layout"><aside className="admin-card map-camera-list"><div className="admin-card-heading"><h2>监控点位</h2><button type="button" className="text-button" onClick={()=>setAttempt(value=>value+1)}>刷新</button></div><label className="sr-only" htmlFor="map-camera-search">查找点位</label><input id="map-camera-search" placeholder="查找点位" value={query} onChange={event=>setQuery(event.target.value)}/><button type="button" className="text-button" aria-pressed={!selected} onClick={()=>setSelected('')}>全部点位</button><div className="map-camera-rows">{filtered.map(value=><article className={selected===value.camera_id?'is-selected':''} key={value.camera_id}><button type="button" className="map-camera-select" onClick={()=>setSelected(value.camera_id)}><strong>{value.display_name}</strong><span>{value.address||'位置已配置'}</span></button><div><a href={'/live?camera='+encodeURIComponent(value.camera_id)}>观看</a><a href={'/?q='+encodeURIComponent(value.display_name+' 的人车')}>检索</a></div></article>)}</div>{!filtered.length&&<p className="admin-empty">未找到匹配点位。</p>}<InfoBubble title="地图与线索关系">地图展示已配置点位，观看入口由实时播放器核实连接状态。候选关联线不代表实际行驶路线。</InfoBubble></aside><div className="map-primary">{camera&&<div className="map-selected-point"><strong>{camera.display_name}</strong><span>{camera.address}</span><a className="admin-button secondary" href={'/live?camera='+encodeURIComponent(camera.camera_id)}>观看实时</a><button type="button" className="text-button" onClick={()=>setSelected('')} aria-label="取消选择点位">×</button></div>}{points.length?<MapCanvas points={points} onSelect={point=>setSelected(point.camera_id)}/>:<div className="admin-empty">尚无已配置坐标的监控点位。</div>}</div></div>}</section>
}
