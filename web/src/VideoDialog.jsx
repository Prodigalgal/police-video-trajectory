import React from 'react'
import {useModal} from './use-modal.js'
import ObservationClipPlayer from './ObservationClipPlayer.jsx'

export default function VideoDialog({ item, onClose, recordingReview=false }) {
  const panel=useModal(onClose)
  return <div className="dialog-backdrop" onMouseDown={event=>{if(event.target===event.currentTarget)onClose()}}><section ref={panel} tabIndex={-1} className="video-dialog" role="dialog" aria-modal="true" aria-labelledby="video-title"><div className="dialog-heading"><div><p className="eyebrow">连续录像回看</p><h2 id="video-title">{item.camera_name||item.camera_id||'监控点位'}</h2></div><button type="button" className="dialog-close" onClick={onClose} aria-label="关闭录像">×</button></div><ObservationClipPlayer key={item.frame_id} item={item} recordingReview={recordingReview}/></section></div>
}
