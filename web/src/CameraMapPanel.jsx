import React,{useEffect,useState} from 'react'
import MapCanvas from './MapCanvas.jsx'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {businessApi} from './investigation-client.js'
import {chineseMessage} from './chinese-copy.js'

export default function CameraMapPanel({onUnauthenticated}){
  const [cameras,setCameras]=useState(null),[selected,setSelected]=useState(''),[error,setError]=useState(''),[attempt,setAttempt]=useState(0)
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
  return <section className="admin-card camera-map-panel" aria-label="监控点位地图"><div className="admin-card-heading"><h2>监控点位地图</h2><div className="admin-row-actions"><InfoBubble title="地图与线索关系">点击监控点位查看信息或观看实时画面。线索集中的候选地图展示所选线索及人工关联；关联线不代表实际行驶路线。</InfoBubble><button type="button" className="text-button" onClick={()=>setAttempt(value=>value+1)}>刷新点位</button></div></div>{error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{!cameras&&!error&&<p role="status">正在读取监控点位…</p>}{cameras&&<><div className="map-point-selection"><label>选择监控点位<select value={selected} onChange={event=>setSelected(event.target.value)}><option value="">全部点位</option>{cameras.map(value=><option key={value.camera_id} value={value.camera_id}>{value.display_name}</option>)}</select></label>{camera&&<div><strong>{camera.display_name}</strong><span>{camera.address}</span><a className="admin-button secondary" href={'/live?camera='+encodeURIComponent(camera.camera_id)}>观看实时监控</a></div>}</div>{points.length?<MapCanvas points={points} onSelect={point=>setSelected(point.camera_id)}/>:<p className="admin-empty">尚无已配置坐标的监控点位。</p>}</>}</section>
}
