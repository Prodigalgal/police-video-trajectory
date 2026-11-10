import React,{useEffect,useRef,useState} from 'react'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {businessApi} from './investigation-client.js'
import {chineseMessage} from './chinese-copy.js'
import {bindingCommand,localCatalogs,localModelErrors,profileLabel} from './local-models.js'

const titles={processing:'车牌与文字专用配置',appearance:'外观模型配置'}
const labels={license_plate:'车牌识别',text_ocr:'文字提取',appearance:'活动外观配置'}
export default function LocalModelsPanel({refresh,onError}){
  const [catalogs,setCatalogs]=useState(null),[drafts,setDrafts]=useState({}),[loading,setLoading]=useState(true),[busy,setBusy]=useState(null),[notice,setNotice]=useState(null),[reload,setReload]=useState(0)
  const active=useRef(null),pending=useRef(null),report=useRef(onError);report.current=onError
  function apply(value,only){
    const parsed=localCatalogs(value)
    setCatalogs(previous=>only&&previous?previous.map(catalog=>catalog.id===only?parsed.find(next=>next.id===only):catalog):parsed)
    setDrafts(previous=>only?{...previous,[only]:{...parsed.find(catalog=>catalog.id===only).bindings}}:Object.fromEntries(parsed.filter(catalog=>catalog.available).map(catalog=>[catalog.id,{...catalog.bindings}])))
    return parsed
  }
  useEffect(()=>{
    const abort=new AbortController();active.current=abort;pending.current=null;setBusy(null);setLoading(true);setCatalogs(null);setNotice(null)
    businessApi('/api/admin/models/local-catalogs',{signal:abort.signal}).then(value=>{if(!abort.signal.aborted)apply(value)}).catch(error=>{if(!abort.signal.aborted){setNotice({error:true,message:localModelErrors[error.code]||chineseMessage(error.message)});if(error.unauthorized)report.current(error)}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[refresh,reload])
  async function save(event,catalog){
    event.preventDefault();const signal=active.current.signal;if(busy||signal.aborted)return
    setBusy(catalog.id);setNotice(null)
    try{
      const command=bindingCommand(catalog,drafts[catalog.id],crypto.randomUUID())
      const fingerprint=JSON.stringify({...command,request_id:null})
      if(pending.current?.fingerprint===fingerprint)command.request_id=pending.current.requestId
      else pending.current={fingerprint,requestId:command.request_id}
      await businessApi('/api/admin/models/local-catalogs/'+catalog.id,{method:'PUT',signal,body:JSON.stringify(command)})
      const value=await businessApi('/api/admin/models/local-catalogs',{signal})
      if(!signal.aborted){const latest=apply(value,catalog.id).find(next=>next.id===catalog.id);pending.current=null;const ready=latest.available&&latest.source_current;setNotice({error:!ready,message:ready?'绑定已保存。后续新任务使用新配置，在途任务保留原版本；采集与索引开关保持原状态。':'绑定已保存，但当前登记目录已变化或暂不可读取，请刷新核验后重新保存。'})}
    }catch(error){if(!signal.aborted){setNotice({error:true,message:localModelErrors[error.code]||chineseMessage(error.message)});if(error.unauthorized)report.current(error)}}
    finally{if(!signal.aborted)setBusy(null)}
  }
  return <section aria-busy={loading}><div className="admin-card-heading"><h2>专用与外观模型</h2><button type="button" className="text-button" disabled={loading||Boolean(busy)} onClick={()=>setReload(value=>value+1)}>刷新配置</button></div>
    {notice&&<FloatingNotice role={notice.error?'alert':'status'} className={notice.error?'notice error':'notice'} onDismiss={()=>setNotice(null)}>{notice.message}</FloatingNotice>}
    {loading&&<p role="status">正在读取已登记模型…</p>}
    <div className="admin-model-grid">{catalogs?.map(catalog=><section key={catalog.id} className="admin-card"><div className="admin-card-heading"><h3>{titles[catalog.id]}</h3><InfoBubble title="绑定与运行边界">仅可选择已登记配置；登记不代表物理模型已加载。保存不会启动采集、索引生产或录像。新任务采用新配置，在途任务保留冻结版本。外观配置按摘要隔离特征空间；没有新模型特征时不会自动补齐索引。</InfoBubble></div>
      {!catalog.available?<p className="admin-empty">该目录尚未配置或暂不可读取。</p>:<form onSubmit={event=>save(event,catalog)}><p className="admin-muted">已登记 {catalog.profiles.length} 个配置 · {catalog.revision===0?'沿用部署目录':`已保存版本 ${catalog.revision}`}{!catalog.source_current&&<InfoBubble title="登记目录已更新">当前部署目录与上次保存的基线不一致，新任务已停止使用旧覆盖。核对下方可选项后重新保存。</InfoBubble>}</p>
        {Object.entries(drafts[catalog.id]||{}).map(([role,id])=><label className="model-local-field" key={role}>{labels[role]}<select value={id??''} disabled={loading||Boolean(busy)} onChange={event=>setDrafts(previous=>({...previous,[catalog.id]:{...previous[catalog.id],[role]:event.target.value||null}}))}>{catalog.id==='processing'&&<option value="">停用此用途</option>}{id&&!catalog.profiles.some(profile=>profile.profile===id)&&<option value={id} disabled>原配置已不在当前目录</option>}{catalog.profiles.filter(profile=>catalog.id==='appearance'||profile.capabilities.includes(role)).map(profile=><option value={profile.profile} key={profile.profile}>{profileLabel(profile,catalog.id)}</option>)}</select></label>)}
        <div className="admin-form-actions"><button type="submit" className="admin-button" disabled={loading||Boolean(busy)}>{busy===catalog.id?'正在保存…':'保存绑定'}</button></div></form>}
    </section>)}</div>
  </section>
}
