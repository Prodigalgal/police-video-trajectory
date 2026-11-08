import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {businessApi,validateTimeline,validateSuggestions,associationReason} from './investigation-client.js'
import {formatOffset,formatTime} from './result-format.js'

export default function InvestigationPanel({detail,onNavigate,onUnauthenticated}){
  const [enabled,setEnabled]=useState(false),[tab,setTab]=useState(''),[data,setData]=useState(null),[error,setError]=useState(''),[loading,setLoading]=useState(false),[reload,setReload]=useState(0)
  const callbacks=useRef({onNavigate,onUnauthenticated});callbacks.current={onNavigate,onUnauthenticated}
  useEffect(()=>{const abort=new AbortController();businessApi('/api/v1/features',{signal:abort.signal}).then(value=>{if(!abort.signal.aborted)setEnabled(value.investigation===true)}).catch(failure=>{if(!abort.signal.aborted&&failure.unauthorized)callbacks.current.onUnauthenticated()});return()=>abort.abort()},[])
  useEffect(()=>{
    const abort=new AbortController();setData(null);setError('')
    if(!tab||!enabled)return()=>abort.abort()
    setLoading(true)
    const path=`/api/v1/investigation/observations/${detail.observation_id}/${tab}?expected_version=${detail.record_version}`
    businessApi(path,{signal:abort.signal}).then(value=>tab==='timeline'?validateTimeline(value,detail):validateSuggestions(value,detail)).then(value=>{if(!abort.signal.aborted)setData({tab,value})}).catch(failure=>{if(!abort.signal.aborted){setError(chineseMessage(failure.message));if(failure.unauthorized)callbacks.current.onUnauthenticated()}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[enabled,tab,detail.observation_id,detail.record_version,detail.camera_id,reload])
  if(!enabled)return null
  const current=data?.tab===tab?data.value:null
  return <section className="evidence-context" aria-label="时序与关联线索"><div className="context-heading"><h3>时序与关联</h3><button type="button" className="text-button" onClick={()=>setTab('')}>收起</button></div>
    <div className="investigation-tabs"><button type="button" className="admin-button secondary" aria-pressed={tab==='timeline'} onClick={()=>setTab('timeline')}>前后时序线索</button><button type="button" className="admin-button secondary" aria-pressed={tab==='candidates'} onClick={()=>setTab('candidates')}>跨点位候选</button></div>
    {loading&&<p role="status">正在整理关联证据…</p>}{error&&<FloatingNotice className="notice error" role="alert">{error}<button type="button" className="text-button" onClick={()=>setReload(value=>value+1)}>重新读取</button></FloatingNotice>}
    {current?.truncated&&<p className="result-note">仅展示当前窗口内的有限候选，可缩小时间范围继续查看。</p>}
    {tab==='timeline'&&current&&<><p className="result-note">按同一读取会话的局部跟踪候选分段；不跨间断补齐录像或确认身份。</p>{!current.clock_available&&<p className="result-note">该记录缺少可比较的片内时钟，仅展示当前观测。</p>}
      <div className="investigation-segments">{current.segments.map(segment=><details key={segment.segment_id} open={segment.contains_anchor}><summary>{segment.contains_anchor?'当前目标候选片段':'邻近目标候选片段'} · {segment.observed_frames} 帧{segment.span_ms!==null&&` · ${Math.round(segment.span_ms/1000)} 秒跨度`}</summary>
        <ol>{segment.observations.map(point=><li key={point.observation_id}><button type="button" className="text-button" disabled={point.observation_id===detail.observation_id||!onNavigate} onClick={()=>callbacks.current.onNavigate(point)}>{point.observation_id===detail.observation_id?'当前观测':`查看 ${formatOffset(point.relative_media_ms)}`}</button><p>{point.description||'暂无描述'}</p></li>)}</ol>
        {segment.actions?.length>0&&<p className="result-note">重复出现的逐帧描述：{segment.actions.map(action=>action.action).join('、')}。连续行为仍需结合原片核验。</p>}
      </details>)}</div></>}
    {tab==='candidates'&&current&&<>{!current.source_time_available?<p className="result-note">来源拍摄时间尚未核实，暂不按跨点位时间关系推荐。</p>:<><p className="result-note">按已知属性和时间推荐，排序值不是身份置信度。可打开详情，将相关线索加入同一线索集再复核关联。</p><ul className="investigation-candidates">{current.items.map(candidate=><li key={candidate.observation.observation_id}><strong>{candidate.observation.camera_name}</strong><p>{candidate.observation.description}</p><p className="admin-muted">{formatTime(candidate.observation.source_time)} · {candidate.reasons.map(associationReason).join('；')}</p><button type="button" className="text-button" disabled={!onNavigate} onClick={()=>callbacks.current.onNavigate(candidate.observation)}>打开候选详情</button></li>)}</ul>{!current.items.length&&<p className="admin-empty">当前范围没有满足条件的候选。</p>}</>}</>}
  </section>
}
