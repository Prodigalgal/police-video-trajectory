import React,{useEffect,useRef,useState} from 'react'
import {businessApi} from './investigation-client.js'
import {appearanceApi} from './appearance-client.js'
import {localCatalogs,profileLabel} from './local-models.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {chineseMessage} from './chinese-copy.js'

export default function AppearanceModelIndexPanel({refresh,onError,onProduced}){
  const [catalog,setCatalog]=useState(null),[selected,setSelected]=useState([]),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false),[notice,setNotice]=useState(null)
  const operation=useRef(null),report=useRef(onError);report.current=onError
  useEffect(()=>{
    const abort=new AbortController();operation.current=abort;setLoading(true);setBusy(false)
    businessApi('/api/admin/models/local-catalogs',{signal:abort.signal}).then(localCatalogs).then(catalogs=>{if(!abort.signal.aborted){const current=catalogs.find(value=>value.id==='appearance');setCatalog(current);setSelected(previous=>previous.filter(id=>current?.profiles?.some(profile=>profile.profile===id&&profile.installed)))}}).catch(error=>{if(!abort.signal.aborted){setNotice({error:true,text:chineseMessage(error.message)});if(error.unauthorized)report.current?.(error)}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[refresh])
  async function produce(event){
    event.preventDefault();if(!selected.length||busy||operation.current.signal.aborted)return
    const signal=operation.current.signal;setBusy(true);setNotice(null)
    try{const value=await appearanceApi('/api/admin/appearance/produce',{method:'POST',signal,body:JSON.stringify({limit:100,profiles:selected})});if(!signal.aborted){setNotice({error:false,text:`本批扫描 ${value.scanned} 个目标，新增 ${value.queued} 个特征任务，复用 ${value.reused} 个。持续生产与领取开关保持原状态。`});onProduced?.()}}
    catch(error){if(!signal.aborted){setNotice({error:true,text:chineseMessage(error.message)});if(error.unauthorized)report.current?.(error)}}finally{if(!signal.aborted)setBusy(false)}
  }
  return <section className="admin-card" aria-busy={loading||busy}><div className="admin-card-heading"><h2>候选模型特征准备</h2><InfoBubble title="多模型对比与索引">最多选择三个已安装模型，为同一批已入库目标分别建立特征，供跨镜关联比较。仅生产有界任务，不切换当前人车模型，不开启持续扫描或索引领取。不同模型特征隔离；准确率仍需真实标注样本评测。</InfoBubble></div>
    {notice&&<FloatingNotice role={notice.error?'alert':'status'} onDismiss={()=>setNotice(null)}>{notice.text}</FloatingNotice>}
    {loading?<p role="status">正在读取已安装模型…</p>:!catalog?.available?<div className="admin-empty">外观模型目录暂不可用。</div>:<form onSubmit={produce}><div className="admin-row-actions">{catalog.profiles.map(profile=><label key={profile.profile} className="admin-check"><input type="checkbox" checked={selected.includes(profile.profile)} disabled={busy||!profile.installed||!selected.includes(profile.profile)&&selected.length>=3} onChange={()=>setSelected(previous=>previous.includes(profile.profile)?previous.filter(id=>id!==profile.profile):[...previous,profile.profile])}/><span>{profileLabel(profile,'appearance')}{!profile.installed?'（未安装）':''}</span></label>)}</div><div className="admin-form-actions"><button type="submit" className="admin-button secondary" disabled={busy||!selected.length||!catalog.source_current}>{busy?'正在准备…':'为所选模型补充一批特征'}</button></div></form>}
  </section>
}
