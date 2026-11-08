import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import MapCanvas from './MapCanvas.jsx'
import {businessApi} from './investigation-client.js'

export default function CollectionMap({collectionId,revision,onSelect,onUnauthenticated}){
  const [enabled,setEnabled]=useState(false),[open,setOpen]=useState(false),[data,setData]=useState(null),[error,setError]=useState(''),[reload,setReload]=useState(0)
  const callbacks=useRef({onSelect,onUnauthenticated});callbacks.current={onSelect,onUnauthenticated}
  useEffect(()=>{const abort=new AbortController();businessApi('/api/v1/features',{signal:abort.signal}).then(value=>{if(!abort.signal.aborted)setEnabled(value.investigation===true)}).catch(failure=>{if(!abort.signal.aborted&&failure.unauthorized)callbacks.current.onUnauthenticated()});return()=>abort.abort()},[])
  useEffect(()=>{
    const abort=new AbortController();setData(null);setError('');if(!open||!enabled)return()=>abort.abort()
    businessApi(`/api/v1/investigation/collections/${collectionId}/map`,{signal:abort.signal}).then(value=>{
      if(!Array.isArray(value.points)||value.points.length>200||!Array.isArray(value.links)||value.coordinate_system!=='GCJ-02'||value.collection?.collection_id!==collectionId||value.collection.revision!==revision)throw new Error('地图对应的清单版本已变化，请刷新清单。')
      if(!abort.signal.aborted)setData(value)
    }).catch(failure=>{if(!abort.signal.aborted){setError(chineseMessage(failure.message));if(failure.unauthorized)callbacks.current.onUnauthenticated()}})
    return()=>abort.abort()
  },[collectionId,revision,open,enabled,reload])
  if(!enabled)return null
  return <section className="admin-card"><div className="admin-card-heading"><div><h2>候选地图</h2><p className="admin-muted">显示点位和已记录的候选关联，虚线不代表实际行驶路径。</p></div><button type="button" className="admin-button secondary" aria-expanded={open} onClick={()=>setOpen(value=>!value)}>{open?'收起地图':'查看高德地图'}</button></div>
    {open&&<>{error?<FloatingNotice className="notice error" role="alert">{error}<button type="button" className="text-button" onClick={()=>setReload(value=>value+1)}>重新读取</button></FloatingNotice>:!data?<p role="status">正在读取地图线索…</p>:<>{data.missing_locations>0&&<p className="result-note">{data.missing_locations} 条线索的点位尚未配置坐标，仍可在时间轴查看。</p>}<MapCanvas points={data.points} links={data.links} onSelect={point=>callbacks.current.onSelect(point.observation_id)}/></>}</>}
  </section>
}
