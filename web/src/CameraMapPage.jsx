import WorkspaceHeader from './WorkspaceHeader.jsx'
import React,{useEffect,useState} from 'react'
import {Login} from './App.jsx'
import {FloatingNotice} from './Feedback.jsx'
import CameraMapPanel from './CameraMapPanel.jsx'

export default function CameraMapPage(){
  const [authenticated,setAuthenticated]=useState(null),[error,setError]=useState(''),[isAdmin,setIsAdmin]=useState(false)
  useEffect(()=>{const controller=new AbortController();fetch('/api/session',{signal:controller.signal}).then(response=>response.json()).then(value=>{if(!controller.signal.aborted){setAuthenticated(value.authenticated);setIsAdmin(value.is_admin===true)}}).catch(problem=>{if(!controller.signal.aborted)setError('登录状态读取失败，请刷新后重试。')});return()=>controller.abort()},[])
  if(authenticated===false)return <Login onLogin={()=>setAuthenticated(true)}/>
  return <div className="app"><WorkspaceHeader active="map" isAdmin={isAdmin}/><main className="camera-map-main">{error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{authenticated===null&&!error?<p role="status">正在验证登录…</p>:authenticated&&<CameraMapPanel onUnauthenticated={()=>setAuthenticated(false)}/>}</main></div>
}
