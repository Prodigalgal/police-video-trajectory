import React,{useEffect,useState} from 'react'
import {VideoDialog} from './App.jsx'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import {formatTime,formatOffset,verifyEvidenceDigest} from './result-format.js'
import {recordingPage,recordingDetail} from './recording-review.js'
import {chineseMessage} from './chinese-copy.js'

const handoffLabels={held:'仅登记，未送分析队列',pending:'等待进入分析队列',submitted:'已提交分析队列'}

function RetainedPreview({row,request,onError,onClose,onPlay}){
  const [detail,setDetail]=useState(null),[image,setImage]=useState(null),[error,setError]=useState(''),[attempt,setAttempt]=useState(0)
  useEffect(()=>{
    const controller=new AbortController();let url
    setDetail(null);setImage(null);setError('')
    async function load(){
      const value=recordingDetail(await request('recording-review/frames/'+row.frame_id,{signal:controller.signal}),row)
      const response=await fetch(`/api/admin/recording-review/frames/${value.frame_id}/image`,{signal:controller.signal,cache:'no-store'})
      if(response.status===401)throw Object.assign(new Error('请重新登录'),{unauthorized:true})
      if(!response.ok)throw new Error('留存关键帧暂不可用，请重新读取。')
      const raw=await response.arrayBuffer()
      if(raw.byteLength!==value.image.bytes||raw.byteLength>8*1024*1024||response.headers.get('x-content-sha256')!==value.payload_sha256||await verifyEvidenceDigest(raw,value.payload_sha256)==='mismatch')throw new Error('原图校验未通过，请重新读取。')
      if(controller.signal.aborted)return
      url=URL.createObjectURL(new Blob([raw],{type:'image/jpeg'}));setImage(url);setDetail(value)
    }
    load().catch(problem=>{if(!controller.signal.aborted&&problem.name!=='AbortError'){setError(chineseMessage(problem.message));if(problem.unauthorized)onError(problem)}})
    return()=>{controller.abort();if(url)URL.revokeObjectURL(url)}
  },[row.frame_id,row.payload_sha256,attempt])
  return <section className="admin-card recording-preview" aria-label="留存帧预览"><div className="admin-card-heading"><h2>{row.camera_name} · 留存关键帧</h2><button type="button" className="text-button" onClick={onClose}>关闭预览</button></div>{error&&<FloatingNotice className="notice error" role="alert">{error}<button className="text-button" type="button" onClick={()=>setAttempt(n=>n+1)}>重新读取</button></FloatingNotice>}{!detail&&!error&&<p role="status">正在核验原图和同会话片段…</p>}{detail&&<div className="recording-preview-body"><img src={image} alt={`${detail.camera_name}的留存关键帧`} onError={()=>setError('浏览器无法显示该关键帧。')}/><div><dl className="evidence-facts"><dt>登记时间</dt><dd>{formatTime(detail.registered_at)}</dd><dt>预检目标</dt><dd>{detail.target_count} 个</dd><dt>片内位置</dt><dd>{formatOffset(detail.video.seek_offset_ms)}</dd><dt>片段时长</dt><dd>{(detail.video.duration_ms/1000).toFixed(2)} 秒</dd><dt>分析交接</dt><dd>{handoffLabels[detail.analysis_handoff]}</dd></dl><button className="admin-button" type="button" onClick={()=>onPlay(detail)}>播放对应片段</button><InfoBubble title="来源与判定说明">原图与片段来自同一采集会话，按源帧时间定位。预检目标数量来自前置检测，不代表已完成语义分析或身份确认。</InfoBubble></div></div>}</section>
}

export default function RecordingReviewPanel({cameras,refresh,onError,request}){
  const [camera,setCamera]=useState(''),[page,setPage]=useState(null),[cursors,setCursors]=useState([null]),[loading,setLoading]=useState(false),[error,setError]=useState(''),[attempt,setAttempt]=useState(0),[selected,setSelected]=useState(null),[player,setPlayer]=useState(null)
  const after=cursors.at(-1)
  useEffect(()=>{
    const controller=new AbortController();setLoading(true);setError('');setPage(null);setSelected(null);setPlayer(null)
    const query=new URLSearchParams({limit:'20',...(camera?{camera_id:camera}:{}),...(after?{after}:{})})
    request('recording-review/frames?'+query,{signal:controller.signal}).then(value=>{const checked=recordingPage(value,camera);if(!controller.signal.aborted)setPage(checked)}).catch(problem=>{if(!controller.signal.aborted&&problem.name!=='AbortError'){setError(chineseMessage(problem.message));if(problem.unauthorized)onError(problem)}}).finally(()=>{if(!controller.signal.aborted)setLoading(false)})
    return()=>controller.abort()
  },[camera,after,refresh,attempt])
  return <><div className="admin-section-title"><div><h1>录像回看</h1><InfoBubble title="留存范围">此处显示已登记同会话片段的留存帧；分析暂停时仍可核验原图和回看。未建立录像绑定的历史帧不列入此处，已有业务片段保持保留。</InfoBubble></div><button type="button" className="admin-button secondary" disabled={loading} onClick={()=>{setCursors([null]);setAttempt(n=>n+1)}}>刷新列表</button></div><section className="admin-card"><div className="admin-filters"><label>监控点位<select value={camera} onChange={event=>{setCamera(event.target.value);setCursors([null])}}><option value="">全部点位</option>{cameras.map(value=><option key={value.camera_id} value={value.camera_id}>{value.display_name||value.camera_id}</option>)}</select></label>{page&&<span className="admin-muted">留存帧 {page.totals.retained_frames} 张 · 片段 {page.totals.retained_segments} 段 · 已登记录像 {(page.totals.retained_video_bytes/1024**2).toFixed(1)} 兆字节</span>}</div>{error&&<FloatingNotice className="notice error" role="alert">{error}<button type="button" className="text-button" onClick={()=>{setCursors([null]);setAttempt(n=>n+1)}}>重新读取</button></FloatingNotice>}<div className="admin-table-wrap" aria-busy={loading}><table className="admin-table"><thead><tr><th>点位</th><th>登记时间</th><th>预检目标</th><th>片段时长</th><th>分析交接</th><th>操作</th></tr></thead><tbody>{page?.items.map(row=><tr key={row.frame_id}><td>{row.camera_name}</td><td>{formatTime(row.registered_at)}</td><td>{row.target_count} 个</td><td>{(row.video.duration_ms/1000).toFixed(2)} 秒</td><td>{handoffLabels[row.analysis_handoff]}</td><td><button className="text-button" type="button" onClick={()=>setSelected(row)}>查看与回放</button></td></tr>)}</tbody></table></div>{loading?<p role="status">正在读取留存帧…</p>:page&&!page.items.length&&<p className="admin-empty">暂无已绑定片段的留存帧。</p>}<div className="admin-list-paging"><button type="button" className="admin-button secondary" disabled={loading||cursors.length===1} onClick={()=>setCursors(values=>values.slice(0,-1))}>上一页</button><span>第 {cursors.length} 页</span><button type="button" className="admin-button secondary" disabled={loading||!page?.has_more} onClick={()=>setCursors(values=>[...values,page.next_cursor])}>下一页</button></div></section>{selected&&<RetainedPreview key={selected.frame_id} row={selected} request={request} onError={onError} onClose={()=>setSelected(null)} onPlay={setPlayer}/>}{player&&<VideoDialog item={player} recordingReview onClose={()=>setPlayer(null)}/>}</>
}
