import React,{useEffect,useMemo,useState} from 'react'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {formatTime} from './result-format.js'
import {chineseMessage} from './chinese-copy.js'
import {plateSearchContext,plateSearchNames,plateSearchPath,reviewedPlateSearchHref,validatePlateSearchStatus} from './reviewed-plate-status.js'

export default function ReviewedPlateSearchPanel({detail,onUnauthenticated,onSourceChanged}){
  const prepared=useMemo(()=>{try{return {context:plateSearchContext(detail)}}catch(error){return {error:error.message}}},[detail])
  const context=prepared.context
  const [attempt,setAttempt]=useState(0),[saved,setSaved]=useState(null),[failure,setFailure]=useState(null)
  const key=context?context.key+':'+attempt:null,value=key&&saved?.key===key?saved.value:null,problem=key&&failure?.key===key?failure:null
  const loading=Boolean(context&&!value&&!problem)
  useEffect(()=>{
    if(!context)return
    const controller=new AbortController(),timer=setTimeout(()=>{
      controller.abort();setFailure({key,message:'车牌检索状态读取超时，请稍后刷新。',sourceChanged:false})
    },30000)
    async function load(){
      const response=await fetch(plateSearchPath(context),{signal:controller.signal,cache:'no-store'})
      if(response.status===401){onUnauthenticated?.();throw new Error('请重新登录。')}
      if(response.status===409)throw Object.assign(new Error('来源或复核依据已更新，请刷新线索。'),{sourceChanged:true})
      if(!response.ok)throw new Error(response.status===404?'当前线索的车牌检索状态不可用。':'车牌检索状态暂不可用，请稍后刷新。')
      const status=validatePlateSearchStatus(await response.json(),context)
      if(!controller.signal.aborted)setSaved({key,value:status})
    }
    load().catch(error=>{if(!controller.signal.aborted)setFailure({key,message:chineseMessage(error.message),sourceChanged:error.sourceChanged===true})}).finally(()=>clearTimeout(timer))
    return()=>{clearTimeout(timer);controller.abort()}
  },[context,key])
  if(!context&&!prepared.error)return null
  const href=value&&reviewedPlateSearchHref(value)
  return <section className="reviewed-plate-search" aria-label="车牌检索状态" aria-busy={loading}>
    <div className="context-heading"><h3>车牌检索</h3><InfoBubble title="复核与检索同步">当前复核依据、同步任务和实际检索文档一致时，才能带入完整牌号搜索。带入后在首页提交搜索；不会自动重新识别或恢复处理。</InfoBubble></div>
    <div className="reviewed-plate-actions"><span role="status">{prepared.error?'复核依据需重新读取':loading?'正在核验检索状态…':problem?'检索状态待核验':plateSearchNames[value?.state]}</span>
      {href?<a className="admin-button secondary" href={href}>带入搜索</a>:<button className="admin-button secondary" type="button" disabled>带入搜索</button>}
      <button className="text-button" type="button" disabled={loading} onClick={()=>{if(prepared.error||problem?.sourceChanged)onSourceChanged?.();else setAttempt(previous=>previous+1)}}>{prepared.error||problem?.sourceChanged?'刷新线索':'刷新状态'}</button>
    </div>{value&&<small className="admin-muted">核验于 {formatTime(value.checked_at)}</small>}
    {(prepared.error||problem)&&<FloatingNotice role="alert" className="error">{prepared.error||problem.message}</FloatingNotice>}
  </section>
}
