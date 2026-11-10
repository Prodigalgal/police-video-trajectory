import {businessApi} from './investigation-client.js'
import {businessCommand} from './business-command.js'

const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=/^[a-f0-9]{64}$/
const maxBytes=516*1024**2
export const exportStates={queued:'等待制作',active:'正在制作',completed:'已完成',failed:'制作失败',cancelled:'已撤销'}
const transferStates={streaming:'正在传输',server_complete:'服务端已发送完成',interrupted:'传输中断',failed:'传输失败'}
const errors={
  exports_disabled:'视频证据包尚未启用。',export_unavailable:'该证据包不可用。',export_queue_full:'导出队列已满，请稍后再提交。',
  export_module_disabled:'视频证据包制作已关闭，已有包仍可下载或撤销。',export_consumer_unavailable:'制作服务暂未在线，请稍后重试。',
  export_expired:'视频证据包已过期，请按当前线索版本重新制作。',
  export_empty:'当前分卷没有可导出的关键帧。',export_size_limit:'本卷附件超过容量限制，请减少随包录像类型。',
  export_revision_conflict:'导出任务已更新，请刷新列表。',export_state_conflict:'任务状态已变化，请刷新列表。',
  export_source_changed:'来源或线索版本已变化，请刷新清单后重新制作。',export_account_changed:'账号信息已变化，请重新登录后重试。',
  export_not_ready:'证据包尚未完成或已被撤销。',export_artifact_changed:'制品校验不一致，任务已停止，请重新制作。',
  export_artifact_unavailable:'证据包文件暂不可用，请重试。',export_asset_unavailable:'录像或原图暂不可读，请重试。',
  export_storage_full:'证据包存储容量已满，请撤销不再需要的旧包并等待回收。',export_storage_busy:'其他制品正在写入，请稍后重试。',
  export_timeout:'制作超时，可检查来源后重试。',export_worker_error:'制作服务暂不可用，请重试。',export_interrupted:'制作已中断，可重试。',
  export_lease_expired:'制作租约已到期，任务将按重试策略恢复。',export_invalid_range:'下载范围无效，请重新开始下载。',
  collection_revision_conflict:'线索集已更新，请刷新清单后重新制作。',collection_unavailable:'线索集已不可用。'
}
export function exportError(code){return errors[code]||'任务暂未完成，请刷新状态或重试。'}
export async function exportApi(path,options={}){
  try{return await businessApi(path,options)}catch(error){if(errors[error.code])error.message=errors[error.code];throw error}
}
const count=(value,min=0,max=Number.MAX_SAFE_INTEGER)=>Number.isSafeInteger(value)&&value>=min&&value<=max
const time=value=>typeof value==='string'&&Number.isFinite(Date.parse(value))
export function validateExportJob(job,collectionId){
  if(!job||!uuid.test(job.export_id)||job.collection_id!==collectionId||!count(job.collection_revision,1)||!count(job.revision,1)
    ||!exportStates[job.state]||!count(job.part,1,100)||!count(job.parts,1,100)||job.part>job.parts||!count(job.attempts,0,3)
    ||!count(job.asset_count,1,12)||!count(job.video_count,0,8)||!count(job.missing_attachment_count,0,8)
    ||typeof job.artifact_cleanup_pending!=='boolean'||!time(job.created_at)||!time(job.updated_at)
    ||(job.last_error_code!==null&&typeof job.last_error_code!=='string')
    ||(job.artifact_sha256!==null&&!sha.test(job.artifact_sha256))||(job.artifact_bytes!==null&&!count(job.artifact_bytes,1,maxBytes))
    ||(job.state==='completed'&&(!sha.test(job.artifact_sha256)||!count(job.artifact_bytes,1,maxBytes))))throw new Error('证据包任务返回不完整，请重新读取。')
  return job
}
export function validateExportPage(value,collectionId){
  validatePage(value,'export_id')
  value.items.forEach(job=>validateExportJob(job,collectionId))
  return value
}
function validatePage(value,key){
  if(!value||!Array.isArray(value.items)||value.items.length>10||typeof value.has_more!=='boolean'
    ||(value.has_more&&(!value.items.length||!uuid.test(value.next_cursor)||value.next_cursor!==value.items.at(-1)?.[key]))
    ||(!value.has_more&&value.next_cursor!==null)||new Set(value.items.map(row=>row?.[key])).size!==value.items.length)throw new Error('导出列表返回不完整，请重新读取。')
}
export function validateExportInfo(value,collection){
  if(!value||value.collection_id!==collection.collection_id||value.collection_revision!==collection.revision||!count(value.parts,1,100)
    ||!count(value.frame_count,0,200)||![2,4].includes(value.frames_per_part)||value.parts!==Math.max(1,Math.ceil(value.frame_count/value.frames_per_part)))throw new Error('证据包分卷记录已变化，请刷新清单。')
  return value
}
export function validateExportModule(value){
  if(!value||value.schema_version!=='export-module-v1'||['installed','enabled','online','can_create'].some(key=>typeof value[key]!=='boolean')||!count(value.version,1)||!count(value.consumer_count,0,16)
    ||!Array.isArray(value.consumer_versions)||value.consumer_versions.length>value.consumer_count||!value.consumer_versions.every(version=>typeof version==='string'&&version.length<=40&&/^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.test(version))
    ||value.online!==(value.consumer_count>0)||value.can_create!==(value.installed&&value.enabled&&value.online)||value.plugin!=='bound_video_package_v1'||!value.installed&&value.online)throw new Error('视频证据包模块状态无效，请刷新后重试。')
  return value
}
export function validateTransferPage(value){
  validatePage(value,'transfer_id')
  for(const record of value.items){
    if(!uuid.test(record.transfer_id)||!sha.test(record.artifact_sha256)||!transferStates[record.state]||typeof record.stale!=='boolean'
      ||!count(record.range_start,0,maxBytes-1)||!count(record.range_end,record.range_start,maxBytes-1)
      ||record.expected_bytes!==record.range_end-record.range_start+1||!count(record.sent_bytes,0,record.expected_bytes)||!time(record.started_at)
      ||(record.finished_at!==null&&!time(record.finished_at)))throw new Error('下载记录返回不完整，请重新读取。')
  }
  return value
}
export function transferLabel(record){return record.state==='streaming'&&record.stale?'结束状态未确认':transferStates[record.state]}
export function exportStateLabel(job){return job.state==='cancelled'&&job.last_error_code==='export_expired'?'已过期':exportStates[job.state]}
export function exportSize(bytes){return count(bytes,0,maxBytes)?`${(bytes/1024**2).toFixed(2)} 二进制兆字节`:'尚未生成'}
export async function exportCommand(user,path,body,collectionId,signal){
  return businessCommand('pvt-export-command-v1',user,path,body,async(url,options)=>validateExportJob(await exportApi(url,options),collectionId),'POST',signal)
}
