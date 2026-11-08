import React,{useEffect,useId,useState} from 'react'
import {InfoBubble,FloatingNotice} from './Feedback.jsx'
import {regionCrop} from './specialist-evidence.js'

/** Rendering only: the caller owns authorized pixels, regions and the selected index. */
export default function EvidenceRegionViewer({source,regions,selected,onSelect,disabled=false,onReady,onRetry}){
  const [mode,setMode]=useState('full'),[load,setLoad]=useState(null)
  const selector=useId(),clip=useId().replaceAll(':',''),identity=source.key+':'+source.url
  const current=regions.find(region=>region.index===selected)
  const ready=load?.identity===identity&&load.ready,failed=load?.identity===identity&&load.failed
  useEffect(()=>{onReady(ready?identity:null);return()=>onReady(null)},[ready,identity,onReady])
  const view=mode==='region'&&current?regionCrop(current.bbox_xyxy,source.width,source.height):[0,0,source.width,source.height]
  function choose(index){onSelect(index);setMode('region')}
  return <section className="region-viewer" aria-label="原图与识别区域核验">
    <div className="region-toolbar"><div role="group" aria-label="画面显示范围"><button className="text-button" type="button" aria-pressed={mode==='full'} disabled={disabled} onClick={()=>setMode('full')}>查看全图</button><button className="text-button" type="button" aria-pressed={mode==='region'} disabled={disabled||!current} onClick={()=>setMode('region')}>放大所选区域</button></div><InfoBubble title="区域核验说明">画面来自摘要校验通过的原始关键帧。框表示识别服务返回的位置，放大只便于观察，不增加原图细节；可用区域选择框和键盘切换，不需要拖动图片。</InfoBubble></div>
    {regions.length>0&&<label htmlFor={selector}>查看识别区域<select id={selector} value={selected} disabled={disabled} onChange={event=>choose(Number(event.target.value))}>{regions.map(region=><option key={region.index} value={region.index}>区域 {region.index+1}：{region.text.slice(0,40)}{region.text.length>40?'…':''}</option>)}</select></label>}
    <div className="region-picture" aria-busy={!ready&&!failed}>
      <svg viewBox={view.join(' ')} width={view[2]} height={view[3]} aria-label={mode==='region'?'所选识别区域的原图放大':'带识别位置的原始画面'}>
        <defs><clipPath id={clip}><rect x={view[0]} y={view[1]} width={view[2]} height={view[3]}/></clipPath></defs>
        <g clipPath={`url(#${clip})`}><image href={source.url} x="0" y="0" width={source.width} height={source.height} onLoad={()=>setLoad({identity,ready:true,failed:false})} onError={()=>setLoad({identity,ready:false,failed:true})}/>
          {ready&&regions.map(region=><rect key={region.index} className={region.index===selected?'region-selected':'region-candidate'} x={region.bbox_xyxy[0]} y={region.bbox_xyxy[1]} width={region.bbox_xyxy[2]-region.bbox_xyxy[0]} height={region.bbox_xyxy[3]-region.bbox_xyxy[1]} vectorEffect="non-scaling-stroke" onClick={()=>{if(!disabled)choose(region.index)}} aria-hidden="true"/>)}
        </g>
      </svg>
    </div>
    {!ready&&!failed&&<p className="result-note" role="status">正在显示已核验的来源原图…</p>}{failed&&<FloatingNotice className="notice error" role="alert">浏览器暂时无法显示已核验画面。<button type="button" className="text-button" onClick={onRetry}>重新读取原图</button></FloatingNotice>}
    {current&&<div className="region-reading"><strong>区域 {current.index+1}</strong><p className="specialist-text">{current.text}</p><p className="admin-muted">原图位置 {current.bbox_xyxy.join('，')}{current.score!==null?` · 模型分值 ${(current.score*100).toFixed(1)}%（未校准）`:' · 无模型分值'}</p></div>}
    <a href={source.url} target="_blank" rel="noreferrer">打开该结果的完整原图</a>
  </section>
}
