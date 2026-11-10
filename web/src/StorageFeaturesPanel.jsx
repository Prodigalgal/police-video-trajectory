import React,{useEffect,useRef,useState} from 'react'
import {businessApi} from './investigation-client.js'
import {recordingCommand,recordingFeature,exportFeature,exportFeatureCommand} from './storage-feature-client.js'
import {commandRequest} from './managed-models.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'

function RecordingFeaturesPanel({refresh,onError}){
  const [feature,setFeature]=useState(null),[draft,setDraft]=useState(null),[loading,setLoading]=useState(true),[saving,setSaving]=useState(false),[notice,setNotice]=useState(null)
  const active=useRef(null),report=useRef(onError);report.current=onError
  function apply(snapshot){const value=recordingFeature(snapshot);setFeature(value);setDraft({enabled:value.enabled,plugin:value.plugin,parameters:value.parameters})}
  useEffect(()=>{
    const abort=new AbortController();active.current=abort;setLoading(true);setSaving(false);setFeature(null);setNotice(null)
    businessApi('/api/admin/pipeline-features',{signal:abort.signal}).then(snapshot=>{if(!abort.signal.aborted)apply(snapshot)}).catch(error=>{if(!abort.signal.aborted){setNotice({error:true,message:error.message});report.current(error)}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[refresh])
  async function save(event){
    event.preventDefault();const signal=active.current.signal;if(saving||!feature||signal.aborted)return
    setSaving(true);setNotice(null)
    try{
      const command=recordingCommand(feature,draft,crypto.randomUUID())
      await businessApi('/api/admin/pipeline-features/frame_recording',{method:'PUT',signal,body:JSON.stringify(command)})
      const latest=await businessApi('/api/admin/pipeline-features',{signal})
      if(!signal.aborted){apply(latest);setNotice({error:false,message:'配置已保存。运行中的采集服务会读取新版本，已暂停的采集不会启动。'})}
    }catch(error){if(!signal.aborted){setNotice({error:true,message:error.code==='pipeline_feature_revision_conflict'?'配置已被其他操作更新，请刷新后重试。':error.message});report.current(error)}}
    finally{if(!signal.aborted)setSaving(false)}
  }
  return <section className="admin-card"><div className="admin-section-title"><h2>可选录像模块</h2><InfoBubble title="空间与生效范围">默认关闭。开启短段会新增录像文件，前后上下文还会增加交叠写入，录像受存储配额限制。启用需要等待下一个关键帧；关闭后停止新增录制并废弃未完成窗口，已留存录像保持。切换仅作用于后续窗口，不会启动已暂停的采集。</InfoBubble></div>
    {loading&&<p role="status">正在读取录像模块…</p>}
    {notice&&<FloatingNotice role={notice.error?'alert':'status'} className={notice.error?'notice error':'notice'}>{notice.message}</FloatingNotice>}
    {feature&&draft&&<form onSubmit={save} className="admin-form"><label className="admin-check"><input type="checkbox" role="switch" checked={draft.enabled} disabled={saving} onChange={event=>setDraft({...draft,enabled:event.target.checked})}/>有效帧录像</label><label>录制策略<select value={draft.plugin} disabled={saving} onChange={event=>setDraft({...draft,plugin:event.target.value,parameters:event.target.value==='context_window_v1'?{seconds:5}:{}})}>{feature.plugins.map(plugin=><option key={plugin.id} value={plugin.id}>{plugin.label}</option>)}</select></label>{draft.plugin==='context_window_v1'&&<label>目标范围<select value={draft.parameters.seconds} disabled={saving} onChange={event=>setDraft({...draft,parameters:{seconds:Number(event.target.value)}})}>{[3,5,10].map(seconds=><option key={seconds} value={seconds}>前后各 {seconds} 秒</option>)}</select></label>}<div className="admin-actions"><span className="admin-muted">已保存：{feature.enabled?'启用':'关闭'} · 版本 {feature.version}</span><button type="submit" className="admin-button" disabled={saving}>{saving?'正在保存…':'保存设置'}</button></div></form>}
  </section>
}

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

export default function StorageFeaturesPanel(props){return <><RecordingFeaturesPanel {...props}/><ExportFeaturesPanel {...props}/></>}
