import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React,{useState} from 'react'
import {chooseSearchCameras,removeSearchCondition,supplementQuery} from './search-guidance.js'

export default function SearchGuidance({result,loading,onAdjust,onRestart}){
  const guidance=result.guidance
  const [selected,setSelected]=useState(()=>result.plan.cameraIds.filter(id=>guidance.camera_choices.some(camera=>camera.camera_id===id)))
  const [error,setError]=useState('')
  function remove(id){
    setError('')
    try{onAdjust(result.query,removeSearchCondition(guidance,id))}catch(problem){setError(chineseMessage(problem.message))}
  }
  function scope(event){
    event.preventDefault();setError('')
    try{onAdjust(result.query,chooseSearchCameras(guidance,selected))}catch(problem){setError(chineseMessage(problem.message))}
  }
  function toggle(id,checked){setSelected(values=>checked?[...values,id]:values.filter(value=>value!==id))}
  return <div className="search-guidance">
    <div className="search-guidance-heading"><h2 id="search-conditions-heading">本次检索条件<InfoBubble title="条件调整说明">{!guidance.conditions.length&&<p>尚未得到可执行条件，请补充描述或选择点位。</p>}<p className="search-guidance-note">{guidance.context===null?'当前条件较多，请修改顶部原描述后重新搜索。':'点击移除后保留其他条件并重新检索；至少保留一个条件。重新理解原描述会恢复按输入解析。'}</p>{guidance.reason==='no_matches'&&<p className="coverage">当前条件没有匹配结果，可以移除一项特征、时间或地点后重查，也可以在下方选择点位。</p>}<p className="search-guidance-note">选择后替换当前地点和点位条件，其余条件保留。</p></InfoBubble></h2><button type="button" className="text-button" disabled={loading} onClick={()=>onRestart(result.query)}>重新理解原描述</button></div>
    <p className="search-original-query">原描述：{result.query}</p>
    <ul className="search-condition-list" aria-label="已理解的检索条件">{guidance.conditions.map(condition=><li key={condition.id}>
      <span>{condition.label}</span><button type="button" disabled={loading||!condition.removable} onClick={()=>remove(condition.id)} aria-label={'移除条件：'+condition.label}>移除</button>
    </li>)}</ul>
    
    
    
    {guidance.context!==null&&<details className="search-scope-picker" open={guidance.reason==='location_unresolved'?true:undefined}>
      <summary>选择监控点位{guidance.reason==='location_unresolved'?'，明确查询地点':''}</summary>
      <form onSubmit={scope}><fieldset disabled={loading}><legend>选择要查询的点位</legend><div className="search-camera-options">{guidance.camera_choices.map(camera=><label key={camera.camera_id}><input type="checkbox" checked={selected.includes(camera.camera_id)} onChange={event=>toggle(camera.camera_id,event.target.checked)}/>{camera.label}</label>)}</div></fieldset>
        {!guidance.camera_choices.length&&<p>当前没有可选点位，请修改搜索描述。</p>}
        <button type="submit" className="primary" disabled={loading||!selected.length}>按所选点位检索</button>
      </form>
    </details>}
    {error&&<FloatingNotice role="alert" className="error">{error}</FloatingNotice>}
  </div>
}

export function SearchClarification({result,loading,onSupplement}){
  const [answer,setAnswer]=useState(''),[error,setError]=useState('')
  const preview=answer.trim()?result.query+'；补充：'+answer.trim():''
  function submit(event){
    event.preventDefault();setError('')
    try{onSupplement(supplementQuery(result.query,answer))}catch(problem){setError(chineseMessage(problem.message))}
  }
  return <section className="search-clarification">
    <h2>还需要一点信息</h2><ul>{result.questions.map((question,index)=><li key={index}>{question}</li>)}</ul>
    <form onSubmit={submit}><label htmlFor="search-supplement">补充或更正描述</label><textarea id="search-supplement" value={answer} onChange={event=>{setAnswer(event.target.value);setError('')}} disabled={loading} maxLength={500} rows={3} placeholder="例如：指的是白色车辆，查 1 号点位" required/>
      <p className="search-guidance-note">补充会按下面的完整描述重新理解；此前手动移除或选择的条件会重新生成。</p>
      {preview&&<div className="search-supplement-preview"><strong>将按完整描述重新搜索</strong><p>{preview}</p><span>{preview.length} / 1000 字</span></div>}
      {error&&<FloatingNotice className="error" role="alert">{error}</FloatingNotice>}
      {preview.length>1000&&<FloatingNotice className="error" role="alert">完整描述超过 1000 字，请缩短补充内容。</FloatingNotice>}
      <button type="submit" className="primary" disabled={loading||!answer.trim()||preview.length>1000}>补充后搜索</button>
    </form>
  </section>
}
