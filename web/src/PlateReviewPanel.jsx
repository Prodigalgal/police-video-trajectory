import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {specialistApi} from './specialist-client.js'

export default function PlateReviewPanel({job,artifact,region,bindingKey,ready,onBusyChange,onUpdated,onUnauthenticated}){
  const review=artifact.plate_review||{state:'unreviewed',revision:0}
  const [acknowledged,setAcknowledged]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState('')
  const checked=ready&&acknowledged===bindingKey
  const actions=useRef(null),requests=useRef(new Map())
  useEffect(()=>{actions.current=new AbortController();return()=>{actions.current.abort();actions.current=null}},[])
  async function submit(decision){
    if(busy||!actions.current||decision==='confirm'&&!checked)return
    const abort=actions.current,selected=decision==='revoke'?review.region_index:region;setBusy(true);onBusyChange(true);setError('')
    const key=[job.job_id,artifact.result_sha256,review.revision,selected,decision,checked].join(':')
    if(!requests.current.has(key))requests.current.set(key,crypto.randomUUID())
    try{
      const value=await specialistApi(`specialist-jobs/${job.job_id}/plate-review`,{method:'POST',signal:abort.signal,body:JSON.stringify({request_id:requests.current.get(key),result_sha256:artifact.result_sha256,region_index:selected,decision,expected_revision:review.revision,original_checked:checked})})
      if(value.job_id!==job.job_id||value.state!==(decision==='confirm'?'confirmed':'revoked')||!Number.isSafeInteger(value.revision)||value.revision!==review.revision+1)throw new Error('复核回执不一致，请重新读取结果。')
      if(!abort.signal.aborted){setAcknowledged(null);onUpdated()}
    }catch(reason){if(!abort.signal.aborted){setError(chineseMessage(reason.message));if(reason.unauthorized)onUnauthenticated()}}
    finally{if(!abort.signal.aborted){setBusy(false);onBusyChange(false)}}
  }
  return <section className="plate-review-panel" aria-label="车牌结果复核"><h4 className="help-heading">车牌区域复核<InfoBubble title="使用与限制说明"><p className="result-note">{review.state==='confirmed'?'所选区域已确认，可作为同一连续候选片段的结果引用。':review.state==='revoked'?'确认已撤销，后续不再据此复用。':'模型结果尚未人工确认。'} 不自动写入其他画面或建立跨点位身份。</p></InfoBubble></h4>
    
    <div className="help-heading">当前复核：区域 {region+1}{!ready&&<InfoBubble title="暂不能确认的原因">请先显示已核验原图并选择与入队牌面相对应的区域。旧服务缺少区域归属依据时只能查看，不能在此确认。</InfoBubble>}</div>
    <label className="result-note"><input type="checkbox" checked={checked} disabled={busy||!ready} onChange={event=>setAcknowledged(event.target.checked?bindingKey:null)}/>我已核对原始画面，确认该区域完整牌号与画面一致</label>
    <div className="admin-row-actions"><button className="admin-button secondary" type="button" disabled={busy||!checked} onClick={()=>submit('confirm')}>确认该区域完整牌号</button>{review.state==='confirmed'&&<button className="text-button" type="button" disabled={busy} onClick={()=>submit('revoke')}>撤销确认</button>}</div>
    {busy&&<p role="status">正在登记复核…</p>}{error&&<FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}
  </section>
}
