import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {businessApi} from './investigation-client.js'
import MapCanvas from './MapCanvas.jsx'

const empty={longitude:'',latitude:'',address:'',reason:''}
export default function CameraGeographyPanel({refresh,onError}){
  const [data,setData]=useState(null),[enabled,setEnabled]=useState(null),[camera,setCamera]=useState(''),[draft,setDraft]=useState(empty),[loading,setLoading]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState(''),[reload,setReload]=useState(0),[mapOpen,setMapOpen]=useState(true)
  const callbacks=useRef(onError),actions=useRef(null),request=useRef(null);callbacks.current=onError
  useEffect(()=>{actions.current=new AbortController();return()=>actions.current.abort()},[])
  useEffect(()=>{
    const abort=new AbortController();setLoading(true);setError('')
    async function load(){const features=await businessApi('/api/v1/features',{signal:abort.signal});if(abort.signal.aborted)return;setEnabled(features.investigation===true);if(!features.investigation){setData([]);return}const value=await businessApi('/api/v1/investigation/cameras',{signal:abort.signal});if(!Array.isArray(value.items))throw new Error('点位坐标记录无效。');if(!abort.signal.aborted)setData(value.items)}
    load().catch(failure=>{if(!abort.signal.aborted){setError(chineseMessage(failure.message));callbacks.current(failure)}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[refresh,reload])
  const selected=data?.find(value=>value.camera_id===camera)
  useEffect(()=>{if(selected)setDraft({...empty,longitude:selected.longitude??'',latitude:selected.latitude??'',address:selected.address});request.current=null;setMessage('')},[selected])
  function field(key,value){setDraft(previous=>({...previous,[key]:value}));request.current=null}
  async function save(event){
    event.preventDefault();if(!selected||busy||!draft.reason.trim())return
    if((draft.longitude==='')!==(draft.latitude==='')){setError('经纬度必须同时填写或同时清空。');return}
    const longitude=draft.longitude===''?null:Number(draft.longitude),latitude=draft.latitude===''?null:Number(draft.latitude)
    if(longitude!==null&&(!Number.isFinite(longitude)||Math.abs(longitude)>180||!Number.isFinite(latitude)||Math.abs(latitude)>90)){setError('经纬度超出有效范围。');return}
    if(!request.current)request.current=crypto.randomUUID();setBusy(true);setError('');setMessage('')
    try{
      const value=await businessApi(`/api/admin/cameras/${encodeURIComponent(camera)}/location`,{method:'PUT',signal:actions.current.signal,body:JSON.stringify({request_id:request.current,expected_revision:selected.revision,longitude,latitude,coordinate_system:'GCJ-02',coordinate_source:selected.coordinate_source||null,address:draft.address,reason:draft.reason.trim()})})
      if(value.camera_id!==camera||value.revision!==selected.revision+1)throw new Error('保存回执不一致，请刷新点位。')
      setMessage('点位坐标已保存。');setReload(value=>value+1)
    }catch(failure){if(!actions.current.signal.aborted){setError(chineseMessage(failure.message));callbacks.current(failure)}}finally{if(!actions.current.signal.aborted)setBusy(false)}
  }
  const points=(data||[]).filter(value=>value.longitude!==null).map(value=>({...value,camera_name:value.display_name}))
  if(enabled===false)return <section className="admin-card"><h1>点位地图</h1><p className="admin-empty">地图模块尚未启用。</p></section>
  return <><div className="admin-section-title"><div><h1>点位地图</h1><p>维护高德地图坐标，供空间展示和候选关系使用。</p></div></div><section className="admin-card">{loading&&<p role="status">正在读取点位…</p>}{error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}{message&&<FloatingNotice className="admin-success" role="status">{message}</FloatingNotice>}
    <label className="candidate-recovery-reason">监控点位<select aria-label="地图监控点位" value={camera} disabled={busy||loading} onChange={event=>setCamera(event.target.value)}><option value="">选择点位</option>{data?.map(value=><option key={value.camera_id} value={value.camera_id}>{value.display_name}</option>)}</select></label>
    {selected&&<form className="geography-form" onSubmit={save}><label>经度（地图坐标）<input type="number" step="any" min="-180" max="180" value={draft.longitude} disabled={busy} onChange={event=>field('longitude',event.target.value)}/></label><label>纬度（地图坐标）<input type="number" step="any" min="-90" max="90" value={draft.latitude} disabled={busy} onChange={event=>field('latitude',event.target.value)}/></label><label>位置说明<input maxLength={500} value={draft.address} disabled={busy} onChange={event=>field('address',event.target.value)}/></label><label>修改说明<input maxLength={300} required value={draft.reason} disabled={busy} onChange={event=>field('reason',event.target.value)}/></label><p className="admin-muted">请填写高德坐标；清空两项可移除地图位置，原点位和线索不删除。</p><button type="submit" className="admin-button" disabled={busy||loading||!draft.reason.trim()}>{busy?'正在保存…':'保存坐标'}</button></form>}
    <button type="button" className="text-button" disabled={busy} onClick={()=>setMapOpen(value=>!value)}>{mapOpen?'收起地图':'打开高德地图选点'}</button>
    {mapOpen&&<><p className="result-note">选择上方点位后，点击地图可填写坐标；保存前仍需填写修改说明。</p><MapCanvas points={points} onSelect={point=>{if(!busy)setCamera(point.camera_id)}} onPick={point=>{if(!busy&&selected){setDraft(value=>({...value,longitude:point.longitude,latitude:point.latitude}));request.current=null}}}/></>}
  </section></>
}
