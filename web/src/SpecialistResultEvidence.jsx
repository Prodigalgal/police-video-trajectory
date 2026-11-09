import React,{useCallback,useEffect,useState} from 'react'
import {InfoBubble,FloatingNotice} from './Feedback.jsx'
import {outcomeNames,limitationNames} from './specialist-client.js'
import {canReviewRegion,evidenceKey} from './specialist-evidence.js'
import EvidenceRegionViewer from './EvidenceRegionViewer.jsx'
import PlateReviewPanel from './PlateReviewPanel.jsx'

export default function SpecialistResultEvidence({job,artifact,previewUrl,sourceError,onRetrySource,onUpdated,onUnauthenticated,onNavigateSource}){
  const initial=artifact.plate_review?.region_index??artifact.plate_review_target?.eligible_region_indices[0]??artifact.result.regions[0]?.index??0
  const [selection,setSelection]=useState({index:initial,generation:0}),[busy,setBusy]=useState(false),[loaded,setLoaded]=useState(null)
  const key=evidenceKey(job,artifact),identity=previewUrl?key+':'+previewUrl:null,ready=identity!==null&&loaded===identity
  const sourceReady=useCallback(value=>setLoaded(value),[])
  useEffect(()=>{setSelection(value=>({index:artifact.plate_review?.region_index??value.index,generation:value.generation+1}))},[artifact.plate_review?.revision])
  const source=artifact.result_source,region=selection.index,result=artifact.result
  function select(index){if(!busy)setSelection(value=>({index,generation:value.generation+1}))}
  return <section className="specialist-result" aria-label="专用识别结果"><h4 className="help-heading">{outcomeNames[result.outcome]} · 待核验<InfoBubble title="结果来源与限制">{job.reused&&<p>当前查看原始来源帧的结果引用，不将牌号自动写入当前画面。</p>}{result.limitations.length>0&&<p>限制：{result.limitations.map(value=>limitationNames[value]).join('、')}</p>}<p>原图区域和读号供人工核验，确认后进入车牌精确检索；不自动认定目标身份。</p></InfoBubble></h4>
    <p className="admin-muted">{result.engine.name} · {result.engine.version} · 结果版本 {artifact.result_revision}</p>
    {source&&onNavigateSource&&<button type="button" className="text-button" disabled={busy} onClick={()=>onNavigateSource(source)}>查看结果来源线索与录像</button>}
    <div className="specialist-source-preview">{previewUrl?<EvidenceRegionViewer source={{key,url:previewUrl,width:result.image_width,height:result.image_height}} regions={result.regions} selected={region} onSelect={select} disabled={busy} onReady={sourceReady} onRetry={onRetrySource}/>:sourceError?<FloatingNotice className="notice error" role="alert">{sourceError}<button type="button" className="text-button" onClick={onRetrySource}>重新读取来源原图</button></FloatingNotice>:<p role="status">正在核验该结果的来源原图…</p>}</div>
    {result.regions.length>0&&<details className="region-all-readings"><summary>查看全部区域文字</summary>{result.regions.map(value=><article key={value.index}><button type="button" className="text-button" disabled={busy} onClick={()=>select(value.index)}>定位区域 {value.index+1}</button><p className="specialist-text">{value.text}</p></article>)}</details>}
    {result.outcome==='unreadable'&&<p>未获得可靠文字，不据此认定画面没有车牌或文字。</p>}
    {artifact.plate_review_available&&!job.reused&&<PlateReviewPanel job={job} artifact={artifact} region={region} bindingKey={[key,artifact.plate_review?.revision||0,region,selection.generation,identity].join(':')} ready={canReviewRegion(artifact,region,ready)} onBusyChange={setBusy} onUnauthenticated={onUnauthenticated} onUpdated={onUpdated}/>}
  </section>
}
