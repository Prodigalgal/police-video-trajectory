import {chineseMessage} from './chinese-copy.js'
const errors={investigation_disabled:'时序线索功能尚未启用。',business_unavailable:'线索分析服务暂不可用。',invalid_business_request:'请求参数无效，请检查条件。',observation_unavailable:'来源线索已不可用，请重新搜索。',observation_revision_conflict:'来源线索已更新，请重新打开详情。',location_revision_conflict:'点位坐标已更新，请刷新后再保存。',command_request_conflict:'请求内容已变化，请刷新后重新提交。',camera_unavailable:'该点位不可管理。'}
export async function businessApi(path,options={}){
  if(!path.startsWith('/api/'))throw new Error('业务接口地址无效。')
  const response=await fetch(path,{...options,cache:'no-store',headers:{'Content-Type':'application/json',...(options.headers||{})}})
  let value;try{value=await response.json()}catch{throw new Error('服务返回了无效记录，请重新读取。')}
  if(!response.ok)throw Object.assign(new Error(errors[value.code]||chineseMessage(value.detail,'业务服务暂不可用，请稍后重试。')),{unauthorized:response.status===401,status:response.status,code:value.code})
  return value
}
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
export function validateObservation(point){
  if(!point||!uuid.test(point.observation_id)||!uuid.test(point.frame_id)||typeof point.camera_id!=='string'||!Number.isSafeInteger(point.record_version)||point.record_version<1||!['person','vehicle'].includes(point.target_type))throw new Error('时序线索记录不一致，请重新读取。')
  return point
}
export function validateTimeline(value,detail){
  validateObservation(value?.anchor)
  if(value.anchor.observation_id!==detail.observation_id||value.anchor.record_version!==detail.record_version||!Array.isArray(value.segments)||value.segments.length>200||typeof value.truncated!=='boolean')throw new Error('时序线索来源不一致，请重新打开详情。')
  let count=0
  for(const segment of value.segments){
    if(!Array.isArray(segment.observations)||!segment.observations.length||segment.identity_verified!==false)throw new Error('候选片段记录不一致。')
    count+=segment.observations.length
    for(const point of segment.observations){validateObservation(point);if(point.camera_id!==detail.camera_id)throw new Error('片段点位不一致。')}
  }
  if(count>200)throw new Error('片段记录超出读取范围。')
  return value
}
export function validateSuggestions(value,detail){
  validateObservation(value?.anchor)
  if(value.anchor.observation_id!==detail.observation_id||value.anchor.record_version!==detail.record_version||!Array.isArray(value.items)||value.items.length>50)throw new Error('候选来源不一致。')
  for(const candidate of value.items){validateObservation(candidate.observation);if(candidate.observation.camera_id===detail.camera_id||candidate.identity_verified!==false||!Array.isArray(candidate.reasons))throw new Error('跨点位候选记录不一致。')}
  return value
}
const reasons={upper_color_matches:'上衣颜色相近',lower_color_matches:'下装颜色相近',vehicle_color_matches:'车身颜色相近',transport_type_matches:'交通工具类型相近',straight_line_time_bound_passed:'直线距离与时间未出现明显冲突'}
export function associationReason(key){return reasons[key]||'属性与时间候选条件'}
