import React,{useEffect,useState} from 'react'
import {Login} from './App.jsx'
import {FloatingNotice} from './Feedback.jsx'
import CameraMapPanel from './CameraMapPanel.jsx'

export default function CameraMapPage(){
  const [authenticated,setAuthenticated]=useState(null),[error,setError]=useState('')
  useEffect(()=>{const controller=new AbortController();fetch('/api/session',{signal:controller.signal}).then(response=>response.json()).then(value=>{if(!controller.signal.aborted)setAuthenticated(value.authenticated)}).catch(problem=>{if(!controller.signal.aborted)setError('登录状态读取失败，请刷新后重试。')});return()=>controller.abort()},[])
  if(authenticated===false)return <Login onLogin={()=>setAuthenticated(true)}/>
  return <div className="app"><header className="topbar"><a className="text-button" href="/">返回检索</a><h1>点位地图</h1><div className="header-tools"><a className="text-button" href="/live">实时监控</a><a className="text-button" href="/clues">线索工作区</a><a className="text-button" href="/image-search">以图搜图</a></div></header><main className="camera-map-main">{error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{authenticated===null&&!error?<p role="status">正在验证登录…</p>:authenticated&&<CameraMapPanel onUnauthenticated={()=>setAuthenticated(false)}/>}</main></div>
}
