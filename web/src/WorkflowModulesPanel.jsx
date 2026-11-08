import {strategyLabel} from './chinese-copy.js'
import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {businessApi} from './investigation-client.js'

export default function WorkflowModulesPanel({refresh,onError}){
  const [data,setData]=useState(null),[map,setMap]=useState(null),[error,setError]=useState(''),[loading,setLoading]=useState(false)
  const report=useRef(onError);report.current=onError
  useEffect(()=>{
    const abort=new AbortController();setLoading(true);setData(null);setMap(null);setError('')
    async function load(){
      const features=await businessApi('/api/v1/features',{signal:abort.signal})
      if(!features.investigation){if(!abort.signal.aborted)setData({enabled:false,aggregation_plugins:[],association_plugins:[],modules:{}});return}
      const [modules,provider]=await Promise.all([businessApi('/api/v1/investigation/capabilities',{signal:abort.signal}),businessApi('/api/admin/map-provider',{signal:abort.signal})])
      if(!abort.signal.aborted){setData(modules);setMap(provider)}
    }
    load().catch(failure=>{if(!abort.signal.aborted){setError(chineseMessage(failure.message));report.current(failure)}}).finally(()=>{if(!abort.signal.aborted)setLoading(false)})
    return()=>abort.abort()
  },[refresh])
  return <><div className="admin-section-title"><div><h1>模块配置</h1><p>查看已安装的策略、启用状态与地图服务。</p></div></div><section className="admin-card">{loading&&<p role="status">正在读取模块…</p>}{error&&<FloatingNotice role="alert" className="notice error">{error}</FloatingNotice>}{data&&!data.enabled&&<p className="admin-empty">时序与地理模块尚未启用，已有搜索和任务保持可用。</p>}{data?.enabled&&<><p className="admin-muted">配置版本 {data.catalog_revision} · 切换策略只影响新的分析查询，已有作业保留原绑定。</p><dl className="evidence-facts"><dt>单镜头分段</dt><dd>{data.modules.temporal_aggregation?.enabled===false?'已停用':strategyLabel(data.modules.temporal_aggregation?.plugin||'local-track-segments-v1')}</dd><dt>候选关联</dt><dd>{data.modules.candidate_association?.enabled===false?'已停用':strategyLabel(data.modules.candidate_association?.plugin||'attribute-time-candidates-v1')}</dd><dt>已安装分段策略</dt><dd>{data.aggregation_plugins.map(strategyLabel).join('、')}</dd><dt>已安装关联策略</dt><dd>{data.association_plugins.map(strategyLabel).join('、')}</dd><dt>地图提供商</dt><dd>高德地图 · {map?.configured?'已配置':'待配置'}</dd><dt>坐标系</dt><dd>地图坐标</dd></dl></>}</section></>
}
