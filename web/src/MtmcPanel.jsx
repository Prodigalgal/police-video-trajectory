import React,{useEffect,useRef,useState} from 'react'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {CollectionPicker} from './ClueActions.jsx'
import {chineseMessage} from './chinese-copy.js'
import {mtmcApi,mtmcCommand,mtmcSourcePosition,mtmcState,mtmcStrategy,mtmcTemporalRelation,validateMtmc,validateMtmcCapabilities} from './mtmc-client.js'
import './mtmc.css'

function sameAnchor(value,detail){return value?.anchor?.observation_id===detail.observation_id&&value.anchor.record_version===detail.record_version}
export default function MtmcPanel({detail,onNavigate,onUnauthenticated}){
  const [cap,setCap]=useState(null),[draft,setDraft]=useState({profiles:[],cameras:[],seconds:600,strategy:'evidence-fusion-v1'})
  const [result,setResult]=useState(null),[busy,setBusy]=useState(false),[loading,setLoading]=useState(true),[notice,setNotice]=useState(null),[picker,setPicker]=useState(null),[reload,setReload]=useState(0)
  const operation=useRef(null),query=useRef(null),pending=useRef(null),callbacks=useRef({onNavigate,onUnauthenticated})
  callbacks.current={onNavigate,onUnauthenticated}
  function report(error){setNotice({error:true,text:chineseMessage(error.message)});if(error.unauthorized)callbacks.current.onUnauthenticated?.()}
  function release(id){if(id)mtmcApi('/queries/'+id,{method:'DELETE',keepalive:true}).catch(()=>{})}
  useEffect(()=>{
    const abort=new AbortController();setLoading(true);setBusy(false);setCap(null);setResult(null);setNotice(null);setPicker(null);pending.current=null
    mtmcApi('/capabilities',{signal:abort.signal}).then(validateMtmcCapabilities).then(value=>{
      if(abort.signal.aborted)return
      setCap(value)
      if(value.enabled){const available=value.profiles.filter(profile=>profile.target_types.includes(detail.target_type));const id=value.active_by_type?.[detail.target_type]||value.active||available[0]?.id;setDraft({profiles:available.some(profile=>profile.id===id)?[id]:[],cameras:[],seconds:600,strategy:value.strategies.some(strategy=>strategy.id==='evidence-fusion-v1')?'evidence-fusion-v1':value.strategies[0].id})}
    }).catch(error=>{if(!abort.signal.aborted)report(error)}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>{abort.abort();operation.current?.abort();release(query.current);query.current=null}
  },[detail.observation_id,detail.record_version,detail.target_type,reload])
  function requestController(){operation.current?.abort();const controller=new AbortController();operation.current=controller;return controller}
  async function create(event){
    event.preventDefault();if(busy||!cap?.enabled)return
    const controller=requestController();setBusy(true);setNotice(null);setPicker(null)
    try{
      let command=mtmcCommand(detail,draft,crypto.randomUUID(),cap.strategies.map(strategy=>strategy.id));const fingerprint=JSON.stringify({...command,request_id:null})
      if(pending.current?.fingerprint===fingerprint)command.request_id=pending.current.id
      else pending.current={fingerprint,id:command.request_id}
      const value=validateMtmc(await mtmcApi('/queries',{method:'POST',body:JSON.stringify(command),signal:controller.signal}),{...detail,profiles:command.profiles,allowedStrategies:cap.strategies.map(strategy=>strategy.id)})
      if(controller.signal.aborted)return
      const previous=query.current;query.current=value.query_id;setResult(value);pending.current=null
      if(previous!==value.query_id)release(previous)
    }catch(error){if(!controller.signal.aborted)report(error)}finally{if(!controller.signal.aborted)setBusy(false)}
  }
  async function page(run,offset=0){
    if(!result||busy)return
    const controller=requestController();setBusy(true);setNotice(null);setPicker(null)
    try{
      const params=new URLSearchParams({run,offset:String(offset),limit:'20'})
      const value=validateMtmc(await mtmcApi('/queries/'+result.query_id+'?'+params,{signal:controller.signal}),{...detail,queryId:result.query_id,run,frozenRuns:result.runs})
      if(!controller.signal.aborted)setResult(value)
    }catch(error){if(!controller.signal.aborted)report(error)}finally{if(!controller.signal.aborted)setBusy(false)}
  }
  async function close(){
    if(!result||busy)return
    const controller=requestController();setBusy(true);setNotice(null)
    try{await mtmcApi('/queries/'+result.query_id,{method:'DELETE',signal:controller.signal});if(!controller.signal.aborted){query.current=null;setResult(null);setPicker(null)}}
    catch(error){if(!controller.signal.aborted)report(error)}finally{if(!controller.signal.aborted)setBusy(false)}
  }
  const current=sameAnchor(result,detail)?result:null,selected=current?.runs.find(run=>run.id===current.selected_run)
  const profiles=cap?.enabled?cap.profiles.filter(profile=>profile.target_types.includes(detail.target_type)):[]
  function chooseProfile(id){setDraft(previous=>({...previous,profiles:previous.profiles.includes(id)?previous.profiles.filter(item=>item!==id):[...previous.profiles,id]}))}
  return <div className="mtmc-panel" aria-busy={busy||loading}>
    {notice&&<FloatingNotice role={notice.error?'alert':'status'} onDismiss={()=>setNotice(null)}>{notice.text}</FloatingNotice>}
    {loading?<p className="mtmc-empty" role="status">正在读取跨镜关联配置…</p>:!cap?.enabled?<div className="mtmc-empty">跨镜关联暂不可用。<button type="button" className="text-button" onClick={()=>setReload(value=>value+1)}>重新读取</button></div>:<>
      <form className="mtmc-form" onSubmit={create}>
        <fieldset disabled={busy}><legend>对比模型 <InfoBubble title="模型对比与特征版本">最多选择三个模型，每个模型按已登记方案分别对比，使用各自冻结的特征空间。模型间分值不直接比较。没有该目标特征的模型会明确标记，不自动启动索引。</InfoBubble></legend><div className="mtmc-models">{profiles.map(profile=><label key={profile.id}><input type="checkbox" checked={draft.profiles.includes(profile.id)} disabled={!draft.profiles.includes(profile.id)&&draft.profiles.length>=3} onChange={()=>chooseProfile(profile.id)}/><span>{profile.label||profile.id}</span>{cap.active_by_type?.[detail.target_type]===profile.id&&<small>当前</small>}</label>)}</div>{!profiles.length&&<span className="admin-muted">该目标类型尚无已登记模型。</span>}</fieldset>
        <div className="mtmc-options"><label>首选方案<select value={draft.strategy} disabled={busy} onChange={event=>setDraft(previous=>({...previous,strategy:event.target.value}))}>{cap.strategies.map(strategy=><option key={strategy.id} value={strategy.id}>{mtmcStrategy(strategy.id,strategy.label)}</option>)}</select></label><label>拍摄时间范围<select value={draft.seconds} disabled={busy} onChange={event=>setDraft(previous=>({...previous,seconds:Number(event.target.value)}))}>{[[60,'前后 1 分钟'],[300,'前后 5 分钟'],[600,'前后 10 分钟'],[1800,'前后 30 分钟'],[3600,'前后 1 小时']].map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label><InfoBubble title="时间与来源边界">仅在拍摄时间已核实时使用时间范围。时间未知时保留外观候选，并明确标记；会话相对位置不能当作跨点位绝对时间。只检索已入库目标，不解析未处理录像。</InfoBubble></div>
        <details className="mtmc-scope"><summary>点位范围 · {draft.cameras.length?`${draft.cameras.length} 个点位`:'全部可访问点位'}</summary><fieldset disabled={busy}><legend className="sr-only">选择跨镜候选点位</legend>{cap.cameras.filter(camera=>camera.camera_id!==detail.camera_id).map(camera=><label key={camera.camera_id}><input type="checkbox" checked={draft.cameras.includes(camera.camera_id)} disabled={!draft.cameras.includes(camera.camera_id)&&draft.cameras.length>=50} onChange={()=>setDraft(previous=>({...previous,cameras:previous.cameras.includes(camera.camera_id)?previous.cameras.filter(id=>id!==camera.camera_id):[...previous.cameras,camera.camera_id]}))}/>{camera.display_name}</label>)}</fieldset></details>
        <div className="mtmc-actions"><button className="admin-button" type="submit" disabled={busy||!draft.profiles.length}>{busy?'正在关联…':'开始跨镜关联与对比'}</button>{current&&<button className="text-button" type="button" disabled={busy} onClick={close}>清除本次结果</button>}<InfoBubble title="关联结果的含义">关联结果是待核实的目标候选，不自动确认身份。外观相关度和综合排序值不是同一目标的概率。可打开原图查看依据，或加入线索集继续复核。</InfoBubble></div>
      </form>
      {busy&&<div className="mtmc-progress" role="status">正在整理已入库的目标特征和关联证据…</div>}
      {current&&<div className="mtmc-results">
        <div className="context-heading"><h4>方案对比</h4>{current.summary.source_time_available===false&&<span className="admin-badge">拍摄时间待核实</span>}<InfoBubble title="对比范围与准确率">下面比较同一次查询中各模型、各策略的候选数量和处理耗时。候选数量不是召回率；准确率需要人工标注的真实跨镜样本评测。分页沿用冻结结果，不重新调用模型。</InfoBubble></div>
        <div className="mtmc-comparison-wrap"><table className="mtmc-comparison"><thead><tr><th>模型 / 方案</th><th>状态</th><th>候选时段</th><th>耗时</th><th><span className="sr-only">查看方案</span></th></tr></thead><tbody>{current.runs.map(run=><tr key={run.id} className={current.selected_run===run.id?'is-selected':''}><td><strong>{cap.profiles.find(profile=>profile.id===run.profile)?.label||run.profile}</strong><span>{mtmcStrategy(run.strategy,run.strategy_label)}</span></td><td>{mtmcState(run.state)}</td><td>{run.total_candidate_periods}{run.truncated?'＋':''}</td><td>{Math.round(run.elapsed_ms)} 毫秒</td><td><button type="button" className="text-button" aria-pressed={current.selected_run===run.id} disabled={busy||run.state!=='completed'} onClick={()=>page(run.id)}>{current.selected_run===run.id?'当前结果':'查看'}</button></td></tr>)}</tbody></table></div>
        <div className="context-heading mtmc-result-heading"><h4>{mtmcStrategy(selected?.strategy,selected?.strategy_label)} · 候选时段</h4>{selected?.truncated&&<InfoBubble title="候选范围已达上限">当前只保留有界候选。请缩小点位或时间范围后再查询，不能将本页候选视为完整轨迹。</InfoBubble>}</div>
        {!current.items.length&&<div className="mtmc-empty">{selected?.state==='feature_missing'?'当前目标尚无此模型特征，请由管理员检查外观索引。':selected?.state==='unavailable'?'该方案暂不可用，可选择其他模型或方案。':'当前范围没有可返回的跨镜候选。'}</div>}
        <ol className="mtmc-candidates">{current.items.map(item=>{const point=item.representative;return <li key={point.observation_id}><div className="mtmc-candidate-heading"><strong>{point.camera_name||'监控点位'}</strong><span className="admin-badge">待核实候选</span></div><p className="mtmc-position">{mtmcSourcePosition(item)}</p><p>{point.description||'暂无中文语义描述'}</p><dl className="mtmc-candidate-facts"><div><dt>拍摄时序</dt><dd>{mtmcTemporalRelation(item.temporal_relation)}</dd></div><div><dt>已见画面</dt><dd>{item.sightings.length} 张</dd></div><div><dt>外观相关度</dt><dd>{item.appearance_similarity.toFixed(3)}</dd></div><div><dt>综合排序值</dt><dd>{item.rank_score.toFixed(3)}</dd></div></dl><details className="mtmc-evidence"><summary>关联依据</summary><ul>{item.reasons.map((reason,index)=><li key={index}>{reason}</li>)}</ul>{item.conflicts?.length>0&&<><strong>待核实差异</strong><ul>{item.conflicts.map((reason,index)=><li key={index}>{reason}</li>)}</ul></>}</details><div className="mtmc-actions"><button type="button" className="text-button" disabled={!onNavigate||busy} onClick={()=>callbacks.current.onNavigate(point)}>查看原图与详情</button><button type="button" className="text-button" disabled={busy} onClick={()=>setPicker(previous=>previous===point.observation_id?null:point.observation_id)}>{picker===point.observation_id?'收起线索集':'加入线索集'}</button></div>{picker===point.observation_id&&<CollectionPicker detail={point} onUnauthenticated={()=>callbacks.current.onUnauthenticated?.()}/>}</li>})}</ol>
        {selected?.state==='completed'&&<div className="admin-list-paging"><button type="button" className="admin-button secondary" disabled={busy||current.offset===0} onClick={()=>page(current.selected_run,Math.max(0,current.offset-20))}>上一页</button><span>第 {Math.floor(current.offset/20)+1} 页</span><button type="button" className="admin-button secondary" disabled={busy||!current.has_more} onClick={()=>page(current.selected_run,current.next_offset)}>下一页</button></div>}
      </div>}
    </>}
  </div>
}
