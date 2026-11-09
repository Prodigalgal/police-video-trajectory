import test from 'node:test'
import assert from 'node:assert/strict'
import {recordingFrame,recordingPage,recordingDetail} from '../src/recording-review.js'
const id=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0')
function frame(){return {frame_id:id(1),camera_id:'camera-1',camera_name:'一号点位',source_type:'rtsp',registered_at:'2026-11-09T00:00:00Z',payload_sha256:'a'.repeat(64),target_count:1,analysis_handoff:'held',video:{available:true,kind:'session_segment',recording_id:id(2),recording_manifest_sha256:'b'.repeat(64),seek_offset_ms:1000,duration_ms:5000,bytes:100}}}
test('留存帧分页绑定点位和唯一游标，旧页或重复帧拒绝',()=>{
  const row=frame(),value={schema_version:'recording-review-v1',items:[row],has_more:true,next_cursor:row.frame_id,totals:{retained_frames:2,retained_segments:2,retained_video_bytes:200},inference_invoked:false}
  assert.equal(recordingPage(value,'camera-1',1),value)
  assert.throws(()=>recordingPage(value,'camera-2',1))
  assert.throws(()=>recordingPage({...value,next_cursor:id(9)},'',1))
  assert.throws(()=>recordingPage({...value,items:[row,row]},'',2))
  for(const patch of [{analysis_handoff:'completed'},{source_type:'unknown'},{payload_sha256:'broken'}])assert.throws(()=>recordingFrame({...row,...patch}))
})
test('播放前元数据必须仍属于同一帧、同一片段和同一原图',()=>{
  const row=frame(),detail={...row,image:{width:1280,height:960,bytes:100,sha256:row.payload_sha256}}
  assert.equal(recordingDetail(detail,row),detail)
  assert.throws(()=>recordingDetail({...detail,frame_id:id(3)},row))
  assert.throws(()=>recordingDetail({...detail,video:{...row.video,seek_offset_ms:1500}},row))
  assert.throws(()=>recordingDetail({...detail,image:{...detail.image,sha256:'c'.repeat(64)}},row))
  assert.throws(()=>recordingDetail({...detail,video:{...row.video,seek_offset_ms:5000}},row))
})
