import {businessApi,validateObservation} from './investigation-client.js'
import {formatOffset,formatTime} from './result-format.js'

const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=/^[a-f0-9]{64}$/,profileId=/^[A-Za-z0-9_-]{1,80}$/
const states=new Set(['completed','feature_missing','unavailable'])
export const mtmcErrors={mtmc_disabled:'跨镜关联尚未启用。',mtmc_query_unavailable:'本次关联已关闭或不可读取，请重新查询。',mtmc_query_expired:'本次关联已过期，请重新查询。',mtmc_scope_too_large:'当前范围过大，请缩小点位或时间范围。',mtmc_query_limit:'关联查询已达上限，请关闭旧查询后重试。',mtmc_profile_unavailable:'所选模型尚不可用于该目标类型，请刷新模型目录。',mtmc_query_busy:'关联服务繁忙，请保留原条件稍后重试。',mtmc_query_running:'本次关联仍在处理，请稍后用原条件重试。',mtmc_query_cancelled:'本次关联已取消，请重新查询。',mtmc_query_timeout:'跨镜关联超时，请缩小范围后重试。',mtmc_request_conflict:'该请求的条件已经变化，请重新发起查询。',mtmc_scope_changed:'可访问点位范围已变化，请重新查询。',mtmc_scope_forbidden:'所选范围包含不可访问点位，请重新选择。',mtmc_source_changed:'来源目标或特征已更新，请重新打开详情后查询。',appearance_scope_too_large:'当前图库范围过大，请缩小点位或时间范围。',appearance_catalog_unavailable:'外观模型目录暂不可用，请稍后重试。',appearance_target_type_unsupported:'所选模型不支持当前目标类型。'}

export async function mtmcApi(path,options={}){
  const abort=new AbortController(),cancel=()=>abort.abort(options.signal?.reason)
  if(options.signal?.aborted)cancel()
  else options.signal?.addEventListener('abort',cancel,{once:true})
  let timedOut=false
  const timer=setTimeout(()=>{timedOut=true;abort.abort()},360000)
  try{return await businessApi('/api/v1/investigation/mtmc'+path,{...options,signal:abort.signal})}
  catch(error){
    if(timedOut)throw new Error('跨镜关联超时，请保留原条件重试。')
    if(mtmcErrors[error.code])error.message=mtmcErrors[error.code]
    throw error
  }finally{clearTimeout(timer);options.signal?.removeEventListener('abort',cancel)}
}

export function validateMtmcCapabilities(value){
  const fail=()=>{throw new Error('跨镜关联配置不一致，请刷新后重试。')}
  if(!value||typeof value.enabled!=='boolean')fail()
  if(!value.enabled)return value
  if(!Array.isArray(value.strategies)||!value.strategies.length||value.strategies.length>8||!Array.isArray(value.profiles)||value.profiles.length>8||!Array.isArray(value.cameras)||value.cameras.length>50)fail()
  const ids=new Set()
  for(const profile of value.profiles){
    if(!profileId.test(profile.id)||ids.has(profile.id)||!sha.test(profile.sha256)||!Array.isArray(profile.target_types)||!profile.target_types.length||new Set(profile.target_types).size!==profile.target_types.length||profile.target_types.some(type=>!['person','vehicle'].includes(type)))fail()
    ids.add(profile.id)
  }
  const plugins=new Set()
  for(const strategy of value.strategies){if(!profileId.test(strategy.id)||plugins.has(strategy.id)||typeof strategy.label!=='string'||!strategy.label||strategy.label.length>100)fail();plugins.add(strategy.id)}
  for(const camera of value.cameras)if(typeof camera.camera_id!=='string'||typeof camera.display_name!=='string')fail()
  return value
}

export function mtmcCommand(detail,draft,requestId,allowedStrategies){
  validateObservation(detail)
  if(!uuid.test(requestId)||!profileId.test(draft.strategy)||allowedStrategies&&!allowedStrategies.includes(draft.strategy)||!Array.isArray(draft.profiles)||!draft.profiles.length||draft.profiles.length>3||new Set(draft.profiles).size!==draft.profiles.length||draft.profiles.some(id=>!profileId.test(id))||!Array.isArray(draft.cameras)||draft.cameras.length>50||new Set(draft.cameras).size!==draft.cameras.length||draft.cameras.some(id=>typeof id!=='string'||!/^[A-Za-z0-9_.-]{1,100}$/.test(id))||!Number.isInteger(draft.seconds)||draft.seconds<1||draft.seconds>3600)throw new Error('请选择一至三个模型，并检查关联范围。')
  return {request_id:requestId,observation_id:detail.observation_id,expected_version:detail.record_version,strategy:draft.strategy,profiles:[...draft.profiles],camera_ids:[...draft.cameras],seconds:draft.seconds,limit:20}
}

export function validateMtmc(value,expected){
  const fail=()=>{throw new Error('关联结果与本次目标或模型版本不一致，请重新查询。')}
  if(value?.schema_version!=='mtmc-results-v1'||!uuid.test(value.query_id)||expected.queryId&&value.query_id!==expected.queryId)fail()
  validateObservation(value.anchor)
  if(value.anchor.observation_id!==expected.observation_id||value.anchor.record_version!==expected.record_version||value.anchor.target_type!==expected.target_type||!value.summary||!Array.isArray(value.runs)||value.runs.length>24||!value.runs.length||!Array.isArray(value.items)||value.items.length>50||!Number.isFinite(Date.parse(value.expires_at)))fail()
  const runs=new Map()
  for(const run of value.runs){
    if(!profileId.test(run.profile)||!sha.test(run.profile_sha256)||!profileId.test(run.strategy)||expected.allowedStrategies&&!expected.allowedStrategies.includes(run.strategy)||run.id!==run.profile+':'+run.strategy||runs.has(run.id)||!states.has(run.state)||!Number.isFinite(run.elapsed_ms)||run.elapsed_ms<0||!Number.isInteger(run.total_candidate_periods)||run.total_candidate_periods<0||run.total_candidate_periods>1000)fail()
    if(expected.profiles&&!expected.profiles.includes(run.profile))fail()
    runs.set(run.id,run)
  }
  const selected=runs.get(value.selected_run)
  if(!selected||expected.run&&value.selected_run!==expected.run||!Number.isInteger(value.offset)||value.offset<0||value.offset>1000||typeof value.has_more!=='boolean'||(value.has_more?(!Number.isInteger(value.next_offset)||value.next_offset<=value.offset||value.next_offset>selected.total_candidate_periods):value.next_offset!==null))fail()
  if(expected.frozenRuns)for(const run of value.runs){const frozen=expected.frozenRuns.find(item=>item.id===run.id);if(!frozen||frozen.profile_sha256!==run.profile_sha256)fail()}
  if(selected.state!=='completed'&&value.items.length)fail()
  const representatives=new Set()
  for(const item of value.items){
    validateObservation(item.representative)
    if(item.representative.target_type!==expected.target_type||item.representative.camera_id===value.anchor.camera_id||representatives.has(item.representative.observation_id)||item.identity_verified!==false||!Number.isFinite(item.appearance_similarity)||item.appearance_similarity< -1.000001||item.appearance_similarity>1.000001||!Number.isFinite(item.rank_score)||!Array.isArray(item.reasons)||item.reasons.length>20||item.reasons.some(reason=>typeof reason!=='string'||reason.length>300)||!Array.isArray(item.sightings)||!item.sightings.length||item.sightings.length>50)fail()
    if(item.conflicts&&(!Array.isArray(item.conflicts)||item.conflicts.length>20||item.conflicts.some(reason=>typeof reason!=='string'||reason.length>300)))fail()
    const period=item.period
    if(!period||!['verified_source','relative_media','unknown'].includes(period.clock))fail()
    if(period.clock==='relative_media'&&(!Number.isSafeInteger(period.start_ms)||period.start_ms<0||!Number.isSafeInteger(period.end_ms)||period.end_ms<period.start_ms))fail()
    if(period.clock==='verified_source'&&(!Number.isFinite(Date.parse(period.start_time))||!Number.isFinite(Date.parse(period.end_time))||Date.parse(period.end_time)<Date.parse(period.start_time)))fail()
    for(const sighting of item.sightings){validateObservation(sighting.observation);if(sighting.observation.camera_id!==item.representative.camera_id||sighting.observation.target_type!==expected.target_type)fail()}
    representatives.add(item.representative.observation_id)
  }
  return value
}

export function mtmcState(state){return {completed:'可查看',feature_missing:'目标尚无该模型特征',unavailable:'本方案暂不可用'}[state]||'状态待核实'}
export function mtmcTemporalRelation(value){return {overlap:'拍摄时段重叠',before:'早于当前目标片段',after:'晚于当前目标片段',unknown:'时序待核实'}[value]||'时序待核实'}
export function mtmcStrategy(id,label){return {'appearance-v1':'仅外观关联','evidence-fusion-v1':'多证据关联'}[id]||(typeof label==='string'&&/[\u3400-\u9fff]/.test(label)?label:'扩展关联方案')}
export function mtmcPeriod(period){
  if(period?.clock==='verified_source')return `${formatTime(period.start_time)} — ${formatTime(period.end_time)}`
  if(period?.clock==='relative_media')return `会话位置 ${formatOffset(period.start_ms)} — ${formatOffset(period.end_ms)}`
  return '来源位置待核实'
}

export function mtmcSourcePosition(item){
  if(item.period?.clock==='verified_source')return mtmcPeriod(item.period)
  const offsets=item.sightings?.map(shot=>shot.clock?.media_offset_ms)||[]
  if(offsets.length&&offsets.every(value=>Number.isSafeInteger(value)&&value>=0))return `源录像偏移 ${formatOffset(Math.min(...offsets))} — ${formatOffset(Math.max(...offsets))}`
  return mtmcPeriod(item.period)
}
