import React,{useEffect,useMemo,useState} from 'react'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {chineseMessage} from './chinese-copy.js'
import {formatTime} from './result-format.js'
import {verifiedTrace,traceStageLabels,traceCheckLabels,traceStateLabels,traceError,traceRequestPath,traceResourceSummary} from './processing-trace.js'

function downloadTrace(trace){
  const url=URL.createObjectURL(new Blob([trace.replay_manifest_json],{type:'application/json;charset=utf-8'}))
  const anchor=document.createElement('a');anchor.href=url;anchor.download=`处理还原记录-${trace.frame_id}.json`;anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
}

export default function ProcessingTracePanel({detail,frameId,onUnauthenticated}){
  const frame=frameId||detail?.frame_id,observation=detail?.observation_id,version=detail?.record_version
  const target=useMemo(()=>({frame,observation,version}),[frame,observation,version])
  const [savedTrace,setTrace]=useState(null),[error,setError]=useState(''),[loading,setLoading]=useState(true),[request,setRequest]=useState({serial:0,target:null,verifyAssets:false})
  const trace=savedTrace&&savedTrace.frame_id===frame&&(!observation||savedTrace.observation_id===observation&&savedTrace.record_version===version)?savedTrace:null
  const verifying=request.target===target&&request.verifyAssets
  useEffect(()=>{
    const controller=new AbortController();setLoading(true);setTrace(null);setError('')
    async function load(){
      const path=traceRequestPath(target,verifying)
      const response=await fetch(path,{signal:controller.signal,cache:'no-store'})
      if(response.status===401){onUnauthenticated?.();throw new Error('请重新登录。')}
      if(!response.ok)throw new Error(traceError(response.status))
      const value=await verifiedTrace(await response.json(),{frame,observation,version})
      if(!controller.signal.aborted)setTrace(value)
    }
    load().catch(problem=>{if(!controller.signal.aborted)setError(chineseMessage(problem.message))}).finally(()=>{if(!controller.signal.aborted)setLoading(false)})
    return()=>controller.abort()
  },[target,request])
  return <section className="processing-trace" aria-label="处理记录" aria-busy={loading}><div className="context-heading"><h3>处理记录</h3><button type="button" className="text-button" disabled={loading} onClick={()=>setRequest(previous=>({serial:previous.serial+1,target,verifyAssets:false}))}>重新校验</button><button type="button" className="text-button trace-resource-action" disabled={loading} onClick={()=>setRequest(previous=>({serial:previous.serial+1,target,verifyAssets:true}))}>{loading&&verifying?'正在核验资源…':'核验关联资源'}</button><InfoBubble title="一致性与准确率">摘要用于核验原图、请求和保存结果的一致性。模型置信度未经校准，检索分值不是身份概率；识别准确率需要独立标注评测。导出的记录可还原已记录的请求与结果，不保证再次调用得到相同输出。主动核验会读取当前检索文档和关联录像摘要，大文件可能稍慢；该操作不启动采集或录制。</InfoBubble></div>{error&&<FloatingNotice className="error" role="alert">{error}</FloatingNotice>}{loading&&<p role="status">{verifying?'正在核验关联资源…':'正在读取并校验处理记录…'}</p>}{trace&&<><div className="trace-checks">{trace.checks.map(check=><span className={`trace-check ${check.state==='changed'||check.state==='missing'?'needs-review':''}`} key={check.name}>{traceCheckLabels[check.name]||'关联记录'} · {traceStateLabels[check.state]||'待核验'}{traceResourceSummary(check)&&<InfoBubble title="关联资源核验">{traceResourceSummary(check)}</InfoBubble>}</span>)}</div><ol className="trace-stages">{trace.stages.map(stage=><li key={stage.name}><strong>{traceStageLabels[stage.name]||'处理环节'}</strong><span>{traceStateLabels[stage.state]||'待核验'}</span>{stage.at&&<time>{formatTime(stage.at)}</time>}{stage.details.configured_model&&<small>模型：{stage.details.configured_model}</small>}{stage.details.prompt_version&&<small>提示词：{stage.details.prompt_version}</small>}{stage.name==='target_detection'&&<small>检测目标 {stage.details.target_count} 个</small>}</li>)}</ol><div className="trace-actions"><button type="button" className="admin-button secondary" onClick={()=>downloadTrace(trace)}>导出还原记录</button>{trace.manifest.image_available&&<a className="text-button" download={`原始关键帧-${frame}.jpg`} href={observation?`/api/v1/frames/${frame}/image`:`/api/admin/processing-traces/frames/${frame}/image?sha=${trace.manifest.image_sha256}`}>下载原始关键帧</a>}<span className="admin-muted">校验时间 {formatTime(trace.checked_at)}</span></div><details className="evidence-record"><summary>摘要与还原依据</summary><dl><dt>还原记录摘要</dt><dd><code>{trace.replay_manifest_sha256}</code></dd><dt>原图摘要</dt><dd><code>{trace.manifest.image_sha256||'未记录'}</code></dd><dt>请求还原</dt><dd>{trace.manifest.request_available?'原图和请求记录完整':'记录尚不完整'}</dd></dl></details></>}</section>
}
