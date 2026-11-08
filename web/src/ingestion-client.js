import {businessApi} from './investigation-client.js'
import {businessCommand} from './business-command.js'

export const ingestionRoot='/api/admin/ingestion'
export const sourceStates={queued:'等待领取',active:'正在采集',disabled:'已暂停',failed:'采集失败',completed:'读取完成'}
export const ingestionErrors={
  ingestion_disabled:'采集管理尚未启用。',source_adapter_unavailable:'该来源适配器尚未安装。',source_unavailable:'该视频来源不可用。',
  ingestion_task_unavailable:'采集任务不可用。',ingestion_source_changed:'来源定义已变化，请重新创建批次。',
  ingestion_revision_conflict:'状态已发生变化，请刷新后重新操作。',ingestion_state_conflict:'任务当前状态不支持此操作。',
  processing_paused:'总领取开关已关闭，请先恢复领取，再启用来源。',source_decode_error:'录像解码失败。',
  source_file_changed:'原录像摘要或大小发生变化，已停止续跑。',source_input_invalid:"来源或 处理节点 能力不符合输入契约。",
  source_input_changed:'来源定义发生变化，已停止领取。',source_checkpoint_gap:'读取或落盘出现缺口，安全进度未推进。',
  source_checkpoint_unavailable:'安全进度暂时无法保存，将从已确认位置恢复。',source_worker_error:"采集发生异常，请结合 处理节点 日志排查。",
  source_interrupted:'采集被中断，等待重新领取。',source_lease_expired:"处理节点 租约已过期。",
  source_replay_window_full:'未确认窗口超过恢复上限，请联系管理员处理。'
}
export async function ingestionApi(path,options={}){
  try{return await businessApi(ingestionRoot+path,options)}
  catch(error){if(ingestionErrors[error.code])error.message=ingestionErrors[error.code];throw error}
}
export function validateIngestionPage(value,key){
  if(!value||!Array.isArray(value.items)||value.items.length>50||typeof value.has_more!=='boolean'||(value.has_more&&typeof value.next_cursor!=='string'))throw new Error('采集列表返回不完整，请重新读取。')
  for(const row of value.items){
    if(typeof row[key]!=='string'||typeof row.camera_id!=='string'||!['rtsp','archive'].includes(row.source_type))throw new Error('采集记录返回不完整，请重新读取。')
    if(key==='task_id'&&(!sourceStates[row.state]||!Number.isSafeInteger(row.revision)||row.revision<1||['registered_frames','analyzed_frames','observations','indexed_observations'].some(field=>!Number.isSafeInteger(row[field])||row[field]<0)))throw new Error('采集状态不完整，请重新读取。')
  }
  return value
}
export function mediaPosition(value){return Number.isSafeInteger(value)&&value>=0?`${(value/1000).toFixed(1)} 秒`:'尚未确认'}

// Only UUIDs and opaque intent hashes are kept. An unknown response can be retried after a page reload.
export async function ingestionCommand(user,path,body,method='POST'){
  return businessCommand('pvt-ingestion-command-v1',user,path,body,ingestionApi,method)
}
