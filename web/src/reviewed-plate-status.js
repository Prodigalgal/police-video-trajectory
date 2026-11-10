const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=/^[a-f0-9]{64}$/
const plate=/^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-HJ-NP-Z](?:[A-HJ-NP-Z0-9]{5}|[DF][A-HJ-NP-Z0-9]{5}|[A-HJ-NP-Z0-9]{5}[DF]|[A-HJ-NP-Z0-9]{4}[警学挂港澳])$/
export const plateSearchNames={disabled:'车牌检索未启用',review_required:'尚未确认牌号',review_conflict:'复核牌号存在冲突',source_changed:'复核来源需重新核对',not_queued:'尚未登记检索同步',pending:'等待同步到检索',retry_wait:'同步异常，等待重试',sync_failed:'检索同步失败',not_indexed:'检索文档尚未就绪',stale:'检索依据已变化',unavailable:'检索状态暂不可核验',searchable:'当前牌号可检索'}
const changed=()=>Object.assign(new Error('复核依据已更新，请刷新线索。'),{sourceChanged:true})
function proofKey(proofs){
  if(!Array.isArray(proofs)||proofs.length>32)throw new Error('车牌复核依据不完整，请重新读取。')
  const keys=proofs.map(proof=>{
    if(!proof||!uuid.test(proof.job_id||'')||!Number.isSafeInteger(proof.review_revision)||proof.review_revision<1||!sha.test(proof.result_sha256||'')||!sha.test(proof.input_manifest_sha256||'')||!Number.isSafeInteger(proof.region_index)||proof.region_index<0||proof.region_index>99||!['single_frame','event'].includes(proof.review_scope))throw new Error('车牌复核依据不完整，请重新读取。')
    return [proof.job_id,proof.review_revision,proof.result_sha256,proof.region_index,proof.input_manifest_sha256,proof.review_scope].join(':')
  })
  if(new Set(proofs.map(proof=>proof.job_id)).size!==proofs.length)throw new Error('车牌复核依据重复，请重新读取。')
  return keys.sort().join('|')
}
export function plateSearchContext(detail){
  if(!detail||detail.target_type!=='vehicle'||!['ready','conflict','invalid_source','source_changed'].includes(detail.reviewed_plate?.state))return null
  if(!uuid.test(detail.observation_id||'')||!uuid.test(detail.frame_id||'')||!Number.isSafeInteger(detail.record_version)||detail.record_version<1||typeof detail.camera_id!=='string'||!detail.camera_id)throw new Error('车牌来源不完整，请重新读取线索。')
  const review=detail.reviewed_plate,key=proofKey(review.proofs||[])
  if(review.state==='ready'&&(!plate.test(review.number||'')||!key))throw new Error('车牌复核依据不完整，请重新读取。')
  return {observation:detail.observation_id,version:detail.record_version,frame:detail.frame_id,camera:detail.camera_id,review:review.state,number:review.number??null,proofs:key,
    key:[detail.observation_id,detail.record_version,detail.frame_id,detail.camera_id,review.state,review.number??'',key].join('/')}
}
export function plateSearchPath(context){return `/api/v1/observations/${encodeURIComponent(context.observation)}/plate-search-status?observation_version=${context.version}`}
export function validatePlateSearchStatus(value,context){
  if(!value||value.schema_version!=='reviewed-plate-search-status-v1'||!Object.hasOwn(plateSearchNames,value.state)||!sha.test(value.fact_sha256||'')||typeof value.checked_at!=='string'||!Number.isFinite(Date.parse(value.checked_at)))throw new Error('车牌检索状态未通过校验，请重试。')
  if(value.observation_id!==context.observation||value.record_version!==context.version||value.frame_id!==context.frame||value.camera_id!==context.camera)throw changed()
  const proofs=proofKey(value.proofs)
  if(proofs!==context.proofs||value.number!==context.number||context.review==='ready'&&['review_required','review_conflict','source_changed'].includes(value.state))throw changed()
  const requested=value.requested_revision,published=value.published_revision
  if(requested===null?published!==null:!Number.isSafeInteger(requested)||requested<1||!Number.isSafeInteger(published)||published<0||published>requested)throw new Error('检索同步版本未通过校验，请重试。')
  if(value.state==='searchable'){
    if(context.review!=='ready'||!plate.test(value.number||'')||!proofs||requested===null||requested!==published||value.query!==`车牌 ${value.number}`)throw changed()
  }else if(value.query!==null)throw new Error('车牌检索状态未通过校验，请重试。')
  return value
}
export function reviewedPlateSearchHref(status){
  if(status?.state!=='searchable'||!plate.test(status.number||'')||status.query!==`车牌 ${status.number}`)return null
  return '/?q='+encodeURIComponent(status.query)
}
