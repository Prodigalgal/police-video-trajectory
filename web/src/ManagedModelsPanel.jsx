import React,{useEffect,useRef,useState} from 'react'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {useModal} from './use-modal.js'
import LocalModelsPanel from './LocalModelsPanel.jsx'
import {formatTime} from './result-format.js'
import {chineseMessage} from './chinese-copy.js'
import {modelErrors,roleLabels,selectionKey,selectionValue,latestProfiles,appliedConsumers,commandRequest} from './managed-models.js'

function ModelDialog({profile,providers,api,onClose,onSaved,onError}){
  const [form,setForm]=useState(()=>({display_name:profile?.display_name||'',provider:profile?.provider||providers[0]?.id||'',model:profile?.model||'',protocol:profile?.protocol||'chat_completions',roles:profile?.roles||['text','vision'],image_transport:profile?.image_transport||'inline',output_mode:profile?.output_mode||'text',json_mode:profile?.json_mode||false,reasoning_effort:profile?.reasoning_effort||''}))
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),request=useRef(null),identity=useRef(profile?.profile_id||crypto.randomUUID())
  const panel=useModal(()=>{if(!busy)onClose()}),provider=providers.find(value=>value.id===form.provider)
  const change=(key,value)=>setForm(previous=>({...previous,[key]:value}))
  async function submit(event){
    event.preventDefault();setBusy(true);setError('')
    try{await api('models/profiles/'+identity.current,{method:'PUT',body:JSON.stringify(commandRequest(request,{expected_version:profile?.version||0,definition:form}))});onSaved()}
    catch(failure){setError(modelErrors[failure.code]||chineseMessage(failure.message));if(failure.unauthorized)onError(failure)}finally{setBusy(false)}
  }
  return <div className="dialog-backdrop model-drawer-backdrop" onMouseDown={event=>{if(event.target===event.currentTarget&&!busy)onClose()}}><section className="admin-retry-dialog model-drawer" role="dialog" aria-modal="true" aria-labelledby="model-dialog-title" ref={panel} tabIndex={-1}>
    <div className="admin-card-heading"><h2 id="model-dialog-title">{profile?'修订模型配置':'新增模型配置'}</h2><button className="dialog-close" type="button" onClick={onClose} disabled={busy} aria-label="关闭模型配置">×</button></div>
    <form onSubmit={submit} aria-busy={busy}>
      <label htmlFor="model-label">显示名称</label><input id="model-label" required maxLength={100} value={form.display_name} onChange={event=>change('display_name',event.target.value)}/>
      <label htmlFor="model-provider">提供商</label><select id="model-provider" value={form.provider} onChange={event=>{const next=providers.find(value=>value.id===event.target.value);setForm(previous=>({...previous,provider:next.id,protocol:next.protocols.includes(previous.protocol)?previous.protocol:next.protocols[0],roles:previous.roles.filter(role=>next.roles.includes(role))}))}}>{providers.map(value=><option key={value.id} value={value.id}>{value.label}{value.activation_allowed?'':'（仅保存）'}</option>)}</select>
      <label htmlFor="model-name">模型名称</label><input id="model-name" required maxLength={150} autoComplete="off" value={form.model} onChange={event=>change('model',event.target.value)}/>
      <label htmlFor="model-protocol">请求协议</label><select id="model-protocol" value={form.protocol} onChange={event=>change('protocol',event.target.value)}>{provider?.protocols.map(value=><option key={value} value={value}>{value==='responses'?'响应协议（Responses）':'对话协议（Chat Completions）'}</option>)}</select>
      <fieldset><legend>可用用途</legend>{Object.entries(roleLabels).map(([role,label])=><label className="admin-checkbox" key={role}><input type="checkbox" checked={form.roles.includes(role)} disabled={!provider?.roles.includes(role)} onChange={event=>change('roles',event.target.checked?[...form.roles,role]:form.roles.filter(value=>value!==role))}/>{label}</label>)}</fieldset>
      <label htmlFor="model-image-mode">图像传输</label><select id="model-image-mode" value={form.image_transport} onChange={event=>change('image_transport',event.target.value)}><option value="inline">请求内嵌图像</option><option value="file">先上传文件再引用</option></select>
      <label htmlFor="model-output-mode">结构化输出</label><select id="model-output-mode" value={form.output_mode==='tool'?'tool':form.json_mode?'json':'text'} onChange={event=>setForm(previous=>({...previous,output_mode:event.target.value==='tool'?'tool':'text',json_mode:event.target.value==='json'}))}><option value="text">文字结果解析</option><option value="json">结构化对象模式</option><option value="tool">工具调用模式</option></select>
      <label htmlFor="model-reasoning">推理强度</label><select id="model-reasoning" value={form.reasoning_effort} onChange={event=>change('reasoning_effort',event.target.value)}>{[['','使用提供商默认'],['none','关闭'],['minimal','极低'],['low','低'],['medium','中'],['high','高'],['xhigh','很高'],['max','最高'],['auto','自动']].map(([value,label])=><option key={value} value={value}>{label}</option>)}</select>
      <InfoBubble title="保存与调用说明">保存产生新修订，不改变当前用途绑定。凭据由服务端管理；不在此页面显示或输入。文件上传和输出模式需由所选模型支持，保存不会自动发起推理。所有调用使用流式请求、360 秒总预算。</InfoBubble>
      {error&&<FloatingNotice role="alert" className="error" onDismiss={()=>setError('')}>{error}</FloatingNotice>}
      <div className="admin-form-actions"><button className="admin-button" type="submit" disabled={busy||!form.roles.length}>{busy?'正在保存…':'保存新修订'}</button><button className="admin-button secondary" type="button" disabled={busy} onClick={onClose}>取消</button></div>
    </form></section></div>
}

export default function ManagedModelsPanel({refresh,onError,api}){
  const [data,setData]=useState(null),[saved,setSaved]=useState(null),[error,setError]=useState(''),[message,setMessage]=useState(''),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false),[dialog,setDialog]=useState(null),[reload,setReload]=useState(0),[selected,setSelected]=useState({text:'',vision:''})
  const report=useRef(onError),request=useRef(null);report.current=onError
  useEffect(()=>{
    const abort=new AbortController();setLoading(true)
    Promise.all([api('models/configuration',{signal:abort.signal}),api('models',{signal:abort.signal})]).then(([value,legacy])=>{if(!abort.signal.aborted){setData(value);setSaved(legacy.saved);setSelected(Object.fromEntries(Object.entries(value.state.bindings).map(([role,binding])=>[role,selectionKey(binding)])))}}).catch(failure=>{if(!abort.signal.aborted){setError(modelErrors[failure.code]||chineseMessage(failure.message));if(failure.unauthorized)report.current(failure)}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[refresh,reload,api])
  useEffect(()=>{
    const abort=new AbortController();let pending=false
    const timer=setInterval(async()=>{if(document.hidden||pending)return;pending=true;try{const value=await api('models/configuration',{signal:abort.signal});if(!abort.signal.aborted)setData(previous=>previous?{...previous,consumers:value.consumers}:previous)}catch(failure){if(!abort.signal.aborted&&failure.unauthorized)report.current(failure)}finally{pending=false}},5000)
    return()=>{abort.abort();clearInterval(timer)}
  },[api])
  async function bind(event){
    event.preventDefault();setBusy(true);setError('');setMessage('')
    try{await api('models/bindings',{method:'POST',body:JSON.stringify(commandRequest(request,{expected_revision:data.state.revision,bindings:Object.fromEntries(Object.entries(selected).map(([role,key])=>[role,selectionValue(key)]))}))});setMessage('用途绑定已保存，等待网关加载。');setReload(value=>value+1)}catch(failure){setError(modelErrors[failure.code]||chineseMessage(failure.message));if(failure.unauthorized)report.current(failure)}finally{setBusy(false)}
  }
  const latest=data?latestProfiles(data.profiles):[],applied=data?appliedConsumers(data):[]
  return <><div className="admin-section-title"><div><h1>模型端点</h1></div><button className="admin-button" disabled={busy||loading||!data?.providers.length} onClick={()=>setDialog({})}>新增模型</button></div>
    {error&&<FloatingNotice role="alert" className="error" onDismiss={()=>setError('')}>{error}</FloatingNotice>}{message&&<FloatingNotice role="status" onDismiss={()=>setMessage('')}>{message}</FloatingNotice>}
    {loading&&<p role="status">正在读取模型配置…</p>}{!loading&&!data&&<button className="admin-button secondary" onClick={()=>setReload(value=>value+1)}>重新读取</button>}
    {data&&<><section className="admin-card"><div className="admin-card-heading"><h2>用途绑定</h2><InfoBubble title="热切换规则">新调用加载新绑定，在途请求继续使用原修订。选择历史修订可以回切；停用用途会拒绝该用途的新调用。不会恢复已暂停的分析队列。网关同步失效超过 30 秒时拒绝新调用。</InfoBubble></div>
      <p className="admin-muted">配置版本 {data.state.revision} · {data.state.revision===0?'尚未接管环境配置':applied.length?`已应用至 ${applied.length} 个网关`:'等待网关加载或心跳已过期'}</p>
      <form className="model-binding-form" onSubmit={bind} aria-busy={busy}><div className="admin-model-grid">{Object.entries(roleLabels).map(([role,label])=>{const active=data.profiles.find(profile=>selectionKey(profile)===selectionKey(data.state.bindings[role]));return <article key={role}><label htmlFor={'binding-'+role}>{label}</label><select id={'binding-'+role} value={selected[role]} onChange={event=>setSelected(previous=>({...previous,[role]:event.target.value}))} disabled={busy||loading}><option value="">停用此用途</option>{data.profiles.filter(profile=>profile.roles.includes(role)).map(profile=>{const provider=data.providers.find(value=>value.id===profile.provider);return <option key={selectionKey(profile)} value={selectionKey(profile)} disabled={!provider?.activation_allowed}>{profile.display_name} · 修订 {profile.version}{provider?.activation_allowed?'':'（仅保存）'}</option>})}</select><p className="admin-muted">当前：{active?`${active.display_name} · 修订 ${active.version}`:data.state.revision?'已停用':'环境配置'}</p></article>})}</div>
        <div className="admin-form-actions"><button className="admin-button" disabled={busy||loading}>{busy?'正在应用…':'应用用途绑定'}</button><button className="admin-button secondary" type="button" disabled={busy} onClick={()=>setReload(value=>value+1)}>刷新配置</button></div>
      </form>{data.consumers.map(consumer=><p className="admin-muted" key={consumer.consumer_id}>网关 {consumer.consumer_id} · 已加载版本 {consumer.revision} · {formatTime(consumer.seen_at)}</p>)}</section>
      <section className="admin-card"><div className="admin-card-heading"><h2>模型配置</h2><span className="admin-muted">共 {latest.length} 个模型</span></div><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>名称</th><th>提供商</th><th>协议</th><th>能力</th><th>状态</th><th>修订</th><th>操作</th></tr></thead><tbody>{latest.map(profile=>{const provider=data.providers.find(value=>value.id===profile.provider);return <tr key={profile.profile_id}><td><strong>{profile.display_name}</strong><details><summary>模型标识与地址</summary><p>{profile.model}</p><p>{profile.base_url}</p></details></td><td>{provider?.label||'提供商不可用'}</td><td>{profile.protocol==='responses'?'响应协议':'对话协议'}</td><td>{profile.roles.map(role=><span className="admin-badge" key={role}>{roleLabels[role]}</span>)}</td><td><span className="admin-badge">{provider?.activation_allowed?'可绑定':'仅保存'}</span></td><td>{profile.version}</td><td><button className="text-button" disabled={busy} onClick={()=>setDialog({profile})}>编辑模型</button></td></tr>})}</tbody></table></div>{!latest.length&&<p className="admin-empty">尚未保存可管理的模型配置。</p>}</section></>}
    {saved?.length>0&&<section className="admin-card"><details><summary>已有端点存档</summary>{saved.map((profile,index)=><article className="admin-saved-model" key={index}><h3>{profile.model}</h3><p>{profile.provider} · {profile.base_url}</p><p className="admin-muted">已保存端点；当前使用状态以上方用途绑定为准。</p></article>)}</details></section>}
    <LocalModelsPanel refresh={refresh} onError={onError}/>
    {dialog&&data&&<ModelDialog profile={dialog.profile} providers={data.providers} api={api} onClose={()=>setDialog(null)} onSaved={()=>{setDialog(null);setMessage('新修订已保存，选择用途绑定后生效。');setReload(value=>value+1)}} onError={onError}/>}</>
}
