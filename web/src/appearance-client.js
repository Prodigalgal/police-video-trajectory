import {readSearchResponse} from './search-response.js'

const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=/^[a-f0-9]{64}$/
const errors={appearance_disabled:'以图搜图尚未启用。',appearance_catalog_unavailable:'外观模型目录暂不可用，请联系管理员。',appearance_profile_conflict:'模型配置已变化，请刷新后重新搜索。',appearance_scope_forbidden:'所选点位不在当前可查范围。',appearance_query_expired:'本次搜索已过期，请重新上传搜索。',appearance_query_unavailable:'本次搜索已不可用，请重新搜索。',appearance_scope_changed:'可查范围已变化，请重新搜索。',appearance_request_conflict:'搜索请求与原条件不一致，请重新搜索。',appearance_scope_too_large:'当前候选范围过大，请限定点位或已核实时间后重试。',appearance_query_limit:'保留的搜索过多，请关闭旧搜索后重试。',appearance_timeout:'本次图像处理超时，请稍后重试。',appearance_search_timeout:'检索耗时过长，请缩小范围后重试。',appearance_scan_conflict:'索引扫描正在更新，请稍后刷新。',appearance_queue_full:'外观索引队列已满，请等待消费后重试。',appearance_retry_conflict:'该任务状态已变化，请刷新后重试。',unauthorized:'登录已失效，请重新登录。'}

export async function appearanceJson(response){
  const value=await response.json()
  if(response.ok)return value
  const problem=new Error(errors[value.code]||'以图搜图暂不可用，请保留条件后重试。')
  problem.unauthorized=response.status===401||value.code==='unauthorized';problem.code=value.code;throw problem
}
export async function appearanceApi(path,options={}){
  return appearanceJson(await fetch(path,{cache:'no-store',...options,headers:{'Content-Type':'application/json',...options.headers}}))
}
export async function uploadAppearance(form,signal){
  return readSearchResponse(await fetch('/api/v1/appearance/queries',{method:'POST',body:form,headers:{Accept:'text/event-stream'},signal}),appearanceJson)
}
export function validBox(box,width,height){
  return Array.isArray(box)&&box.length===4&&box.every(Number.isFinite)&&Number.isInteger(width)&&Number.isInteger(height)&&width>0&&height>0&&box[0]>=0&&box[1]>=0&&box[2]<=width&&box[3]<=height&&box[2]-box[0]>=12&&box[3]-box[1]>=12
}
export function validateAppearance(value,expected){
  const fail=()=>{throw new Error('相似候选与本次搜索不一致，请重新搜索。')}
  if(value?.schema_version!=='appearance-results-v1'||value.query_id!==expected.id||!uuid.test(value.query_id)||!Array.isArray(value.items)||value.items.length>20||value.summary?.profile_sha256!==expected.profile||value.summary?.image_sha256!==expected.image||value.summary?.target_type!==expected.type||!sha.test(expected.image)||!sha.test(expected.profile))fail()
  if(!Number.isInteger(value.offset)||value.offset<0||value.offset>1000||!Number.isInteger(value.total_candidate_periods)||value.total_candidate_periods<0||value.total_candidate_periods>1000||typeof value.has_more!=='boolean'||(value.has_more?(!Number.isInteger(value.next_offset)||value.next_offset<=value.offset||value.next_offset>value.total_candidate_periods):value.next_offset!==null))fail()
  const ids=new Set()
  for(const hit of value.items){
    if(!uuid.test(hit.observation_id)||!uuid.test(hit.frame_id)||!uuid.test(hit.target_id)||ids.has(hit.observation_id)||!Number.isSafeInteger(hit.record_version)||hit.record_version<1||typeof hit.camera_id!=='string'||hit.camera_id.length>100||hit.target_type!==expected.type||!sha.test(hit.image_sha256)||!validBox(hit.bbox_xyxy,hit.image_width,hit.image_height)||!Number.isFinite(hit.similarity)||hit.similarity< -1||hit.similarity>1||!Number.isInteger(hit.sightings)||hit.sightings<1||hit.sightings>50)fail()
    const period=hit.period
    if(!period||!['verified_source','relative_media','unknown'].includes(period.clock)||period.association!=='local_geometry_candidate')fail()
    if(period.clock!=='unknown'&&(!Number.isSafeInteger(period.start_ms)||period.start_ms<0||!Number.isSafeInteger(period.end_ms)||period.end_ms<period.start_ms||period.end_ms-period.start_ms>300000))fail()
    if(period.clock==='verified_source'&&(!Number.isFinite(Date.parse(hit.period_start))||!Number.isFinite(Date.parse(hit.period_end))))fail()
    ids.add(hit.observation_id)
  }
  return value
}
