import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {businessApi,validateSuggestions,associationReason} from './investigation-client.js'
import {clueApi} from './clue-client.js'
import {formatTime} from './result-format.js'

export default function AssociationSuggestions({collection,nodes,onUpdated,onInspect,onUnauthenticated}){
  const [enabled,setEnabled]=useState(false),[source,setSource]=useState(''),[data,setData]=useState(null),[loading,setLoading]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState(''),[reload,setReload]=useState(0)
  const callbacks=useRef({onUpdated,onInspect,onUnauthenticated}),action=useRef(null);callbacks.current={onUpdated,onInspect,onUnauthenticated}
  useEffect(()=>{action.current=new AbortController();return()=>action.current.abort()},[])
  useEffect(()=>{const abort=new AbortController();businessApi('/api/v1/features',{signal:abort.signal}).then(value=>{if(!abort.signal.aborted)setEnabled(value.investigation===true)}).catch(failure=>{if(!abort.signal.aborted&&failure.unauthorized)callbacks.current.onUnauthenticated()});return()=>abort.abort()},[])
  const anchor=nodes.find(node=>node.observation_id===source&&node.available)
  useEffect(()=>{
    const abort=new AbortController();setData(null);setError('');if(!enabled||!anchor)return()=>abort.abort()
    setLoading(true)
    businessApi(`/api/v1/investigation/observations/${source}/candidates?expected_version=${anchor.record_version}`,{signal:abort.signal}).then(value=>validateSuggestions(value,anchor)).then(value=>{if(!abort.signal.aborted)setData(value)}).catch(failure=>{if(!abort.signal.aborted){setError(chineseMessage(failure.message));if(failure.unauthorized)callbacks.current.onUnauthenticated()}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[enabled,source,anchor?.record_version,collection.collection_id,reload])
  async function adopt(candidate){
    if(busy||collection.state!=='open'||!anchor)return;setBusy(true);setError('');setMessage('')
    try{
      let current=await clueApi('/'+collection.collection_id,{signal:action.current.signal})
      const sourceNode=current.nodes.find(node=>node.observation_id===source&&node.available&&node.record_version===anchor.record_version)
      if(!sourceNode)throw new Error('来源线索已有变化，请刷新清单。')
      const target=candidate.observation
      const existingNode=current.nodes.find(node=>node.observation_id===target.observation_id)
      if(existingNode&&(!existingNode.available||existingNode.record_version!==target.record_version))throw new Error('候选线索已有变化，请重新读取建议。')
      if(!current.nodes.some(node=>node.observation_id===target.observation_id))current=await clueApi(`/${collection.collection_id}/items/${target.observation_id}`,{method:'PUT',signal:action.current.signal,body:JSON.stringify({expected_revision:current.collection.revision,observation_version:target.record_version})})
      const existing=current.links.find(link=>link.from_observation_id===source&&link.to_observation_id===target.observation_id)
      if(!existing)current=await clueApi(`/${collection.collection_id}/links/${source}/${target.observation_id}`,{method:'PUT',signal:action.current.signal,body:JSON.stringify({expected_revision:current.collection.revision,state:'pending',reason:'系统候选建议：'+candidate.reasons.map(associationReason).join('；')+'。仅作为待复核关联。'})})
      if(!action.current.signal.aborted){callbacks.current.onUpdated(current);setMessage(existing?'已有对应关联，保留原复核状态。':'候选已加入清单，关联等待复核。')}
    }catch(failure){if(!action.current.signal.aborted){setError(chineseMessage(failure.message)+' 已成功加入的线索会保留，可刷新后继续。');if(failure.unauthorized)callbacks.current.onUnauthenticated()}}finally{if(!action.current.signal.aborted)setBusy(false)}
  }
  if(!enabled)return null
  return <section className="admin-card"><div className="admin-card-heading"><div><h2>关联建议</h2><p className="admin-muted">根据一条线索查找其他点位的时间与属性候选。</p></div></div>
    <label className="candidate-recovery-reason">来源线索<select aria-label="关联建议来源线索" value={source} disabled={busy} onChange={event=>{setSource(event.target.value);setMessage('')}}><option value="">选择一条线索</option>{nodes.filter(node=>node.available).map(node=><option value={node.observation_id} key={node.observation_id}>{node.camera_name} · {node.observation_id.slice(0,8)}</option>)}</select></label>
    {loading&&<p role="status">正在查找候选…</p>}{error&&<FloatingNotice className="notice error" role="alert">{error}<button type="button" className="text-button" disabled={busy} onClick={()=>setReload(value=>value+1)}>重新读取建议</button></FloatingNotice>}{message&&<FloatingNotice className="admin-success" role="status">{message}</FloatingNotice>}
    {data&&!data.source_time_available&&<p className="result-note">来源拍摄时间未核实，暂不推荐跨点位关联。</p>}
    {data&&data.source_time_available&&<><ul className="investigation-candidates">{data.items.map(candidate=><li key={candidate.observation.observation_id}><strong>{candidate.observation.camera_name}</strong><p>{candidate.observation.description}</p><p className="admin-muted">{formatTime(candidate.observation.source_time)} · {candidate.reasons.map(associationReason).join('；')}</p><div className="admin-row-actions"><button type="button" className="text-button" disabled={busy} onClick={()=>callbacks.current.onInspect(candidate.observation)}>查看候选</button><button type="button" className="admin-button secondary" disabled={busy||collection.state!=='open'} onClick={()=>adopt(candidate)}>记录为待复核关联</button></div></li>)}</ul>{!data.items.length&&<p className="admin-empty">当前时间范围没有满足条件的候选。</p>}{data.truncated&&<p className="result-note">当前为有界候选结果，可通过另一条观测继续查找。</p>}</>}
  </section>
}
