const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=/^[a-f0-9]{64}$/
export function validateNearbyRecords(value,detail){
  const invalid=()=>{throw new Error('附近留存画面记录已变化，请重新读取。')}
  if(value?.schema_version!=='nearby-records-v1'||value.time_basis!=='recorded_at'||value.sequence_eligible!==false||value.continuous_video_verified!==false||value.anchor?.observation_id!==detail.observation_id||value.anchor.frame_id!==detail.frame_id||value.anchor.record_version!==detail.record_version||!Array.isArray(value.items)||value.items.length>17)invalid()
  let previous=-Infinity;const ids=new Set()
  for(const frame of value.items){
    const time=Date.parse(frame.received_at)
    if(!uuid.test(frame.frame_id)||!uuid.test(frame.observation_id)||frame.camera_id!==detail.camera_id||frame.source_type!==detail.source_type||!sha.test(frame.preview_sha256)||!Number.isFinite(time)||time<previous||!Number.isSafeInteger(frame.record_offset_ms)||ids.has(frame.frame_id))invalid()
    ids.add(frame.frame_id);previous=time
  }
  if(!ids.has(detail.frame_id))invalid()
  return value
}
