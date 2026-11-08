import {verifyEvidenceDigest} from './result-format.js'

const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=/^[a-f0-9]{64}$/
const messages={observation_unavailable:'该线索已不可用。',observation_revision_conflict:'线索已有更新，请重新打开详情。',input_source_unavailable:'来源画面已不可用。',input_source_changed:'来源记录已变化，请重新读取。',input_sequence_unavailable:'同段附近缺少符合间隔要求的画面，暂不能保存序列。',input_too_large:'所选画面超过大小限制，请缩小窗口。',input_evidence_changed:'画面摘要或尺寸已变化，请重新核验。',input_evidence_unavailable:'原始画面暂不可用。',input_request_conflict:'保存请求内容不一致，请重新打开详情。',input_unavailable:'该分析输入已不可用。',input_manifest_changed:'输入清单一致性校验失败，请联系管理员。',input_frame_unavailable:'该帧不属于当前输入。',invalid_input_request:'输入参数无效，请重新读取。'}
const fail=()=>{throw new Error('邻近画面记录不一致，请重新读取。')}
const millis=value=>Number.isSafeInteger(value)&&value>=0

export function validateContext(value,detail,window){
  if(value?.anchor?.observation_id!==detail.observation_id||value.anchor.frame_id!==detail.frame_id||value.anchor.record_version!==detail.record_version||!Array.isArray(value.frames)||!value.frames.length||value.frames.length>window.max_frames||typeof value.truncated!=='boolean'||value.quality?.continuous_video_verified!==false||value.quality?.identity_association_verified!==false)fail()
  if(Object.keys(window).some(key=>value.window?.[key]!==window[key]))fail()
  const center=value.frames.find(frame=>frame.frame_id===detail.frame_id),ids=new Set()
  if(!center)fail()
  for(const frame of value.frames){
    if(!uuid.test(frame.frame_id||'')||ids.has(frame.frame_id)||!uuid.test(frame.task_id||'')||frame.camera_id!==detail.camera_id||frame.source_type!==detail.source_type||!sha.test(frame.source_fingerprint||'')||typeof frame.ambiguous_time!=='boolean'||typeof frame.anchor_segment!=='boolean')fail()
    ids.add(frame.frame_id)
    if(frame.task_id!==center.task_id||frame.scene_run_id!==center.scene_run_id||frame.media_epoch!==center.media_epoch)fail()
    if(frame.source_time_origin!=='verified'&&frame.source_time!==null)fail()
    if(frame.registered_payload_sha256!==null&&!sha.test(frame.registered_payload_sha256||''))fail()
    if(center.relative_media_ms===null){if(value.frames.length!==1||frame.scene_run_id!==null||frame.media_epoch!==null)fail()}
    else if(!millis(frame.relative_media_ms)||!uuid.test(frame.scene_run_id||'')||!millis(frame.media_epoch)||frame.relative_media_ms<center.relative_media_ms-window.before_ms||frame.relative_media_ms>center.relative_media_ms+window.after_ms)fail()
  }
  if(value.frames.some((frame,index)=>index>0&&frame.relative_media_ms<value.frames[index-1].relative_media_ms))fail()
  let first=value.frames.indexOf(center),last=first
  const connected=(a,b)=>!a.ambiguous_time&&!b.ambiguous_time&&millis(a.relative_media_ms)&&millis(b.relative_media_ms)&&b.relative_media_ms>a.relative_media_ms&&b.relative_media_ms-a.relative_media_ms<=window.max_gap_ms
  while(first>0&&connected(value.frames[first-1],value.frames[first]))first--
  while(last+1<value.frames.length&&connected(value.frames[last],value.frames[last+1]))last++
  const selected=value.frames.slice(first,last+1).map(frame=>frame.frame_id),eligible=selected.length>1&&!center.ambiguous_time
  const reason=center.relative_media_ms===null?'clock_unavailable':center.ambiguous_time?'ambiguous_time':eligible?'sampled_sequence':'insufficient_nearby_frames'
  if(value.sequence?.eligible!==eligible||value.sequence.reason!==reason||JSON.stringify(selected)!==JSON.stringify(value.sequence.frame_ids)||value.frames.some(frame=>frame.anchor_segment!==selected.includes(frame.frame_id)))fail()
  return value
}

export function validateInput(value,observation){
  if(!value||!uuid.test(value.input_id||'')||value.anchor_observation_id!==observation||!uuid.test(value.anchor_frame_id||'')||!Number.isSafeInteger(value.anchor_record_version)||value.anchor_record_version<1||!['single_frame','frame_sequence'].includes(value.purpose)||!sha.test(value.manifest_sha256||'')||!['not_revalidated','stale'].includes(value.source_status)||value.creation_invoked_inference!==false)throw new Error('分析输入记录不一致，请刷新重试。')
  return value
}
export function validateInputPage(value,observation){
  if(!Array.isArray(value?.items)||value.items.length>50||typeof value.has_more!=='boolean'||(value.has_more?!uuid.test(value.next_cursor||''):value.next_cursor!==null))throw new Error('分析输入分页无效。')
  value.items.forEach(input=>validateInput(input,observation))
  if(new Set(value.items.map(input=>input.input_id)).size!==value.items.length)throw new Error('分析输入分页重复。')
  return value
}
async function errorFor(response){
  let value={};try{value=await response.json()}catch{/* An unreadable response is a service failure. */}
  return Object.assign(new Error(messages[value.code]||'分析输入服务暂不可用，请稍后重试。'),{code:value.code,unauthorized:response.status===401})
}
export async function inputApi(path,options={}){
  const response=await fetch('/api/v1/'+path,{...options,cache:'no-store',headers:{'Content-Type':'application/json',...(options.headers||{})}})
  if(!response.ok)throw await errorFor(response)
  return response.json()
}
export async function readBounded(response,maxBytes){
  if(!response.body)throw new Error('响应内容为空。')
  const reader=response.body.getReader(),chunks=[];let size=0
  try{while(true){const chunk=await reader.read();if(chunk.done)break;size+=chunk.value.byteLength;if(size>maxBytes){await reader.cancel();throw new Error('响应内容超过大小限制。')}chunks.push(chunk.value)}}finally{reader.releaseLock()}
  const bytes=new Uint8Array(size);let offset=0
  for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.byteLength}return bytes.buffer
}
export async function previewFrame(frame,signal){
  if(!sha.test(frame.registered_payload_sha256||''))throw new Error('该旧帧缺少登记摘要，暂不能核验预览。')
  const response=await fetch(`/api/v1/frames/${frame.frame_id}/image`,{cache:'no-store',signal})
  if(!response.ok)throw await errorFor(response)
  const bytes=await readBounded(response,8*1024*1024)
  if(await verifyEvidenceDigest(bytes,frame.registered_payload_sha256)!=='valid')throw new Error('当前浏览器无法完成画面摘要校验。')
  return new Blob([bytes],{type:'image/jpeg'})
}
export async function downloadInput(input,signal){
  const response=await fetch(`/api/v1/analysis-inputs/${input.input_id}/manifest`,{cache:'no-store',signal})
  if(!response.ok)throw await errorFor(response)
  const bytes=await readBounded(response,512*1024)
  if(!globalThis.crypto?.subtle)throw new Error('当前浏览器无法校验清单。')
  const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),n=>n.toString(16).padStart(2,'0')).join('')
  if(digest!==response.headers.get('x-content-sha256')||digest!==input.manifest_sha256)throw new Error('清单摘要不一致，请重新读取。')
  const manifest=JSON.parse(new TextDecoder().decode(bytes))
  if(manifest.schema_version!=='analysis-input-v1'||manifest.anchor?.observation_id!==input.anchor_observation_id||manifest.anchor?.record_version!==input.anchor_record_version||manifest.purpose!==input.purpose||manifest.quality?.creation_invoked_inference!==false||manifest.quality?.continuous_video_verified!==false)throw new Error('清单与当前输入不一致。')
  return new Blob([bytes],{type:'application/json'})
}
