import React,{useEffect,useRef,useState} from 'react'
import {businessApi} from './investigation-client.js'
import {exportFeature,exportFeatureCommand} from './storage-feature-client.js'
import {commandRequest} from './managed-models.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'

function ExportFeaturesPanel({refresh,onError}){
  const [feature,setFeature]=useState(null),[draft,setDraft]=useState(null),[loading,setLoading]=useState(true),[saving,setSaving]=useState(false),[notice,setNotice]=useState(null)
  const active=useRef(null),command=useRef(null),report=useRef(onError);report.current=onError
  function apply(snapshot){const value=exportFeature(snapshot);setFeature(value);setDraft({enabled:value.enabled,plugin:value.plugin,parameters:value.parameters})}
  useEffect(()=>{
    const abort=new AbortController();active.current=abort;setLoading(true);setNotice(null);setFeature(null);setSaving(false)
    businessApi('/api/admin/pipeline-features',{signal:abort.signal}).then(value=>{if(!abort.signal.aborted)apply(value)}).catch(error=>{if(!abort.signal.aborted){setNotice({error:true,message:error.message});report.current(error)}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[refresh])
  async function save(event){
    event.preventDefault();const signal=active.current.signal;if(saving||!feature||signal.aborted)return
    setSaving(true);setNotice(null)
    try{
      const response=await businessApi('/api/admin/pipeline-features/evidence_export',{method:'PUT',signal,body:JSON.stringify(commandRequest(command,exportFeatureCommand(feature,draft)))})
      if(!signal.aborted){apply(response);setNotice({error:false,message:'视频证据包设置已保存，采集和录像开关保持原状态。'})}
    }catch(error){if(!signal.aborted){setNotice({error:true,message:error.code==='pipeline_feature_revision_conflict'?'配置已更新，请刷新后重试。':error.code==='pipeline_feature_not_installed'?'制作服务尚未安装，请联系管理员。':error.message});report.current(error)}}
    finally{if(!signal.aborted)setSaving(false)}
  }
  return <section className="admin-card"><div className="admin-section-title"><h2>视频证据包模块</h2><InfoBubble title="制作与空间范围">默认关闭。开启后可制作附带录像的证据包，使用独立限额空间；关闭后在途任务可完成，已有包仍可下载、撤销，满二十四小时后回收。</InfoBubble></div>
    {loading&&<p role="status">正在读取视频证据包模块…</p>}
    {notice&&<FloatingNotice role={notice.error?'alert':'status'} className={notice.error?'notice error':'notice'}>{notice.message}</FloatingNotice>}
    {feature&&draft&&<form onSubmit={save} className="admin-form"><label className="admin-check"><input type="checkbox" role="switch" checked={draft.enabled} disabled={saving||(!feature.installed&&!draft.enabled)} onChange={event=>setDraft({...draft,enabled:event.target.checked})}/>允许制作视频证据包</label><label>制作策略<select value={draft.plugin} disabled={saving||!feature.installed} onChange={event=>setDraft({...draft,plugin:event.target.value,parameters:{}})}>{feature.plugins.map(plugin=><option key={plugin.id} value={plugin.id}>{plugin.label}</option>)}</select></label><div className="admin-actions"><span className="admin-muted">{feature.installed?'已安装':'尚未安装'} · 已保存：{feature.enabled?'启用':'关闭'} · 版本 {feature.version}</span><button className="admin-button" type="submit" disabled={saving||(!feature.installed&&draft.enabled)}>{saving?'正在保存…':'保存设置'}</button></div></form>}
  </section>
}

export default function StorageFeaturesPanel(props){return <ExportFeaturesPanel {...props}/>}
