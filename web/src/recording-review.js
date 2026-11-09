const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const digest=/^[a-f0-9]{64}$/
export const recordingReviewErrors={recording_review_disabled:'录像回看尚未启用。',recording_unavailable:'该留存帧或片段不可用，请刷新列表。',recording_cursor_invalid:'列表已变化，请从第一页重新读取。',recording_source_conflict:'帧与录像来源不一致，已停止播放。',recording_clock_conflict:'录像定位信息不一致，已停止播放。',recording_pixels_changed:'原始画面校验未通过，请重新读取。',recording_file_unavailable:'录像文件暂不可用。',recording_manifest_changed:'录像清单已变化，请重新读取。'}
function invalid(){throw new Error('留存帧资料无效，请刷新后重试。')}
export function recordingFrame(value,camera=''){
  if(!value||!uuid.test(value.frame_id)||typeof value.camera_id!=='string'||!value.camera_id.match(/^[A-Za-z0-9_-]{1,100}$/)||camera&&value.camera_id!==camera||typeof value.camera_name!=='string'||!['rtsp','archive'].includes(value.source_type)||!Number.isFinite(Date.parse(value.registered_at))||!digest.test(value.payload_sha256)||!Number.isInteger(value.target_count)||value.target_count<0||value.target_count>1000||!['held','pending','submitted'].includes(value.analysis_handoff))invalid()
  const video=value.video
  if(!video||video.available!==true||video.kind!=='session_segment'||!uuid.test(video.recording_id)||!digest.test(video.recording_manifest_sha256)||!Number.isInteger(video.seek_offset_ms)||!Number.isInteger(video.duration_ms)||video.seek_offset_ms<0||video.duration_ms<1||video.duration_ms>20000||video.seek_offset_ms>=video.duration_ms||!Number.isInteger(video.bytes)||video.bytes<1||video.bytes>64*1024*1024)invalid()
  return value
}
export function recordingPage(value,camera='',limit=20){
  if(value?.schema_version!=='recording-review-v1'||!Array.isArray(value.items)||value.items.length>limit||typeof value.has_more!=='boolean'||value.inference_invoked!==false)invalid()
  const ids=new Set()
  for(const row of value.items){recordingFrame(row,camera);if(ids.has(row.frame_id))invalid();ids.add(row.frame_id)}
  if(value.has_more?value.items.length!==limit||value.next_cursor!==value.items.at(-1).frame_id:value.next_cursor!==null)invalid()
  for(const key of ['retained_frames','retained_segments','retained_video_bytes'])if(!Number.isSafeInteger(value.totals?.[key])||value.totals[key]<0)invalid()
  return value
}
export function recordingDetail(value,expected){
  recordingFrame(value,expected.camera_id)
  for(const key of ['frame_id','payload_sha256'])if(value[key]!==expected[key])invalid()
  for(const key of ['recording_id','recording_manifest_sha256','seek_offset_ms','duration_ms','bytes'])if(value.video[key]!==expected.video[key])invalid()
  const image=value.image
  if(!image||image.sha256!==value.payload_sha256||!Number.isInteger(image.width)||!Number.isInteger(image.height)||image.width<1||image.height<1||image.width*image.height>50000000||!Number.isInteger(image.bytes)||image.bytes<1||image.bytes>8*1024*1024)invalid()
  return value
}
