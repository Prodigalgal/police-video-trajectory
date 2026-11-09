import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React, { useEffect, useState } from 'react'
import { useModal } from './use-modal.js'
import { evidenceBox, formatOffset, formatTime, uncertainLabels, verifyEvidenceDigest } from './result-format.js'
import {CollectionPicker,ReviewPanel} from './ClueActions.jsx'
import TemporalContextPanel from './TemporalContextPanel.jsx'
import MediaPreparationPanel from './MediaPreparationPanel.jsx'
import InvestigationPanel from './InvestigationPanel.jsx'
import ObservationSpecialistPanel from './ObservationSpecialistPanel.jsx'
import ObservationClipPlayer from './ObservationClipPlayer.jsx'
import {videoOffsetMs} from './video-position.js'

export default function EvidenceDialog({ item, onClose, onPlay, onUnauthenticated, reviewContext, onNavigate }) {
  const panel = useModal(onClose)
  const [detail,setDetail] = useState(null), [image,setImage] = useState(null), [natural,setNatural] = useState(null)
  const [digest,setDigest] = useState('pending'), [error,setError] = useState(''), [loading,setLoading] = useState(true), [showBox,setShowBox] = useState(true), [reviewRefresh,setReviewRefresh] = useState(0), [showAdvanced,setShowAdvanced] = useState(false)
  useEffect(() => {
    const controller = new AbortController(); let temporary
    async function load() {
      setLoading(true); setError('');setImage(null);setNatural(null);setDigest('pending')
      const response = await fetch(`/api/v1/observations/${encodeURIComponent(item.observation_id)}`, { signal:controller.signal, cache:'no-store' })
      if (response.status === 401) { onUnauthenticated(); return }
      if (!response.ok) throw new Error(response.status === 404 ? '该线索已不可用，请重新搜索。' : '暂时无法读取线索详情，请稍后重试。')
      const data = await response.json()
      if (data.observation_id !== item.observation_id || !/^[a-f0-9-]{36}$/.test(data.frame_id || '')) throw new Error('线索记录不一致，请重新搜索。')
      if (controller.signal.aborted) return
      setDetail(data)
      if (!data.evidence?.available) return
      if (data.evidence.capture_integrity === 'mismatch') throw new Error('关键帧与原登记摘要不一致，暂不显示画面，请联系管理员核验。')
      const original = await fetch(`/api/v1/frames/${encodeURIComponent(data.frame_id)}/image`, { signal:controller.signal, cache:'no-store' })
      if (original.status === 401) { onUnauthenticated(); return }
      if (!original.ok) throw new Error('关键帧暂不可用，请稍后重新打开。')
      const bytes = await original.arrayBuffer(), verified = await verifyEvidenceDigest(bytes, data.evidence.sha256)
      if (controller.signal.aborted) return
      temporary = URL.createObjectURL(new Blob([bytes], { type:'image/jpeg' })); setImage(temporary); setDigest(verified)
    }
    load().catch(reason => { if (!controller.signal.aborted) setError(chineseMessage(reason.message)) }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => { controller.abort(); if (temporary) URL.revokeObjectURL(temporary) }
  }, [item.observation_id,reviewRefresh])
  const box = evidenceBox(detail,natural,digest), attributes = detail?.attributes || {}, unknowns = uncertainLabels(detail?.uncertain_fields)
  const tags = [detail?.target_type === 'person' && attributes.upper_color && `上衣 ${attributes.upper_color}`, detail?.target_type === 'person' && attributes.lower_color && `下装 ${attributes.lower_color}`, detail?.reviewed_plate?.state === 'ready' && `已复核车牌 ${detail.reviewed_plate.number}`, attributes.vehicle_color && `车身 ${attributes.vehicle_color}`, ...(Array.isArray(attributes.carrying) ? attributes.carrying : []), attributes.transport_type, attributes.visible_action].filter(Boolean)
  const imageUrl = detail?.frame_id ? `/api/v1/frames/${encodeURIComponent(detail.frame_id)}/image` : null
  return <div className="dialog-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose() }}>
    <section className="evidence-dialog" ref={panel} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="evidence-title" aria-busy={loading}>
      <header className="evidence-heading"><div><p className="eyebrow">线索详情</p><h2 id="evidence-title">{detail?.camera_name || item.camera_id} · {item.target_type === 'vehicle' ? '车辆' : '人物'}线索</h2></div><button type="button" className="dialog-close" onClick={onClose} aria-label="关闭线索详情">×</button></header>
      <div className="evidence-body"><div className="evidence-visual"><div className="evidence-picture">
        {loading && <p className="evidence-placeholder" role="status">正在读取关键帧和线索详情…</p>}
        {image && <img src={image} alt={`${detail?.camera_name || item.camera_id}的原始关键帧`} onLoad={event => setNatural({ width:event.currentTarget.naturalWidth,height:event.currentTarget.naturalHeight })} onError={() => setError('浏览器无法显示这张关键帧。')}/>}
        {box && showBox && <svg viewBox={`0 0 ${natural.width} ${natural.height}`} preserveAspectRatio="xMidYMid meet" aria-hidden="true"><rect x={box.x} y={box.y} width={box.width} height={box.height} fill="none" stroke="#ffda66" strokeWidth="3" vectorEffect="non-scaling-stroke"/></svg>}
        {!loading && !image && !error && <p className="evidence-placeholder">原始关键帧暂不可用。</p>}
      </div>{error && <FloatingNotice className="notice error" role="alert">{error}</FloatingNotice>}
      {image && <div className="evidence-image-tools">{box ? <label><input type="checkbox" checked={showBox} onChange={event => setShowBox(event.target.checked)}/>显示同帧检测候选框</label> : <span>该记录暂无可靠绑定的定位框</span>}<a href={imageUrl} target="_blank" rel="noreferrer">查看原图</a><a href={imageUrl} download={`关键帧-${detail.frame_id}.jpg`}>下载关键帧</a></div>}
      {image && digest === 'unavailable' && <p className="result-note">原图已载入，当前浏览器未完成一致性校验，因此不叠加目标框。</p>}
      {!loading&&detail?.observation_id===item.observation_id&&(detail.video?.available?<ObservationClipPlayer key={`clip:${detail.frame_id}`} item={detail} onExpand={context=>onPlay({...detail,...context})}/>:<section className="evidence-context"><div className="context-heading"><h3>画面前后视频</h3><InfoBubble title="回看与实时画面">当前记录尚未关联连续录像片段。新帧会按同会话位置关联录像，已留存画面可在辅助工具中逐张核对。</InfoBubble><a className="text-button" href="/live">观看实时监控</a></div></section>)}
      {!loading && detail?.observation_id===item.observation_id && <ObservationSpecialistPanel onReviewChanged={()=>{setDetail(current=>current?{...current,reviewed_plate:{state:'pending'}}:current);setReviewRefresh(value=>value+1)}} key={`specialists:${detail.observation_id}:${detail.record_version}`} detail={detail} onUnauthenticated={onUnauthenticated} onNavigateSource={onNavigate}/>}
      {!loading&&detail?.observation_id===item.observation_id&&<details className="evidence-advanced" onToggle={event=>setShowAdvanced(event.currentTarget.open)}><summary>辅助关键帧与高级分析</summary><InfoBubble title="辅助工具用途">逐张核对相邻关键帧、保存分析输入或准备分析片段；连续回看使用上方视频。</InfoBubble>{showAdvanced&&<>
      {!loading && detail?.observation_id===item.observation_id && <TemporalContextPanel key={`${detail.observation_id}:${detail.record_version}`} detail={detail} digest={digest} onUnauthenticated={onUnauthenticated} onNavigateSource={onNavigate}/>}
      {!loading && detail?.observation_id===item.observation_id && <MediaPreparationPanel key={`media:${detail.frame_id}:${detail.record_version}`} detail={detail} digest={digest} onUnauthenticated={onUnauthenticated}/>}
      </>}</details>}
      {!loading && detail?.observation_id===item.observation_id && <InvestigationPanel key={`investigation:${detail.observation_id}:${detail.record_version}`} detail={detail} onNavigate={onNavigate} onUnauthenticated={onUnauthenticated}/>}
      </div><div className="evidence-info">
        {detail && <><p className="evidence-description">{detail.description || '该目标暂无文字描述。'}</p>{item.record_version !== undefined && detail.record_version !== item.record_version && <p className="result-note">该记录已有更新，当前展示最新详情。</p>}
        {item.match_reasons?.length > 0 && <p className="match-reasons">相关特征：{item.match_reasons.join('、')}</p>}{tags.length > 0 && <div className="tags">{tags.map((tag,i) => <span key={`${tag}-${i}`}>{tag}</span>)}</div>}
        {unknowns.length > 0 && <p className="result-note">无法确定：{unknowns.join('、')}</p>}
        <dl className="evidence-facts"><dt>来源</dt><dd>{detail.source_type === 'archive' ? '历史录像' : '实时监控流'}</dd>
        <dt>{detail.source_time ? '来源时间' : '记录时间'}</dt><dd>{formatTime(detail.source_time || detail.received_at) || '未知'}</dd>
        <dt>{detail.video?.kind === 'session_segment' ? '留存片段位置' : '片内位置'}</dt><dd>{formatOffset(videoOffsetMs(detail))}</dd></dl>
        {detail.video?.available && <button type="button" className="primary evidence-play" onClick={() => onPlay({...detail,video_context_mode:'full'})}>查看完整录像</button>}
        {detail.reviewed_plate?.state==='ready'&&<details className="evidence-record"><summary>车牌复核依据</summary><InfoBubble title="车牌关联范围">仅确认当前画面，不建立跨帧或跨点位身份。撤销复核或来源变化后不再用于检索。</InfoBubble><dl><dt>完整车牌</dt><dd>{detail.reviewed_plate.number}</dd>{detail.reviewed_plate.proofs.map(proof=><React.Fragment key={proof.job_id}><dt>识别任务</dt><dd>{proof.job_id}</dd><dt>复核版本</dt><dd>{proof.review_revision}</dd><dt>结果摘要</dt><dd>{proof.result_sha256}</dd></React.Fragment>)}</dl></details>}
        <details className="evidence-record"><summary>证据记录</summary><dl><dt>观测编号</dt><dd>{detail.observation_id}</dd><dt>模型</dt><dd>{detail.model_id}</dd><dt>识别版本</dt><dd>{detail.prompt_version}</dd><dt>记录版本</dt><dd>{detail.record_version}</dd>{detail.evidence?.sha256 && <><dt>关键帧 校验摘要</dt><dd>{detail.evidence.sha256}</dd></>}</dl></details>
        {reviewContext?<ReviewPanel context={reviewContext} detail={detail} digest={digest} onUnauthenticated={onUnauthenticated}/>:<CollectionPicker detail={detail} onUnauthenticated={onUnauthenticated}/>}
        <p className="result-note">描述与检测框均为辅助线索，请结合原始画面核验。</p></>}
      </div></div>
    </section>
  </div>
}
