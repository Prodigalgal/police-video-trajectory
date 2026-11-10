const digestPattern=/^[a-f0-9]{64}$/
export function traceRequestPath({frame,observation,version},verifyAssets=false){
  if(typeof frame!=='string'||!frame||observation&&(!Number.isSafeInteger(version)||version<1))throw new Error('当前处理记录尚未准备好。')
  const path=observation?`/api/v1/observations/${encodeURIComponent(observation)}/trace?observation_version=${version}`:`/api/admin/processing-traces/frames/${encodeURIComponent(frame)}`
  return path+(verifyAssets?(observation?'&':'?')+'verify_assets=true':'')
}
export function traceResourceSummary(check){
  const details=check?.details
  if(!details)return ''
  if(check.name==='elasticsearch_document'){
    if(details.reason==='observation_limit_exceeded')return '当前帧关联观测超过单次核验范围，本次未做完整对照。'
    const count=name=>Number.isSafeInteger(details[name])&&details[name]>=0&&details[name]<=100?details[name]:0
    if(details.expected===0)return '当前帧尚无可对照的检索观测。'
    return `需对照 ${count('expected')} 条，一致 ${count('matched')} 条，缺失 ${count('missing')} 条，变化 ${count('changed')} 条，暂不可读 ${count('unavailable')} 条。`
  }
  if(check.name==='recording_file'){
    if(check.state==='matched')return '录像文件摘要、关键帧绑定及片内时间位置已核验一致。'
    if(check.state==='missing')return '当前记录没有可读取的已登记录像。'
    return check.state==='changed'?'录像登记、来源或文件内容已变化，请重新核对。':'录像文件暂不可核验，请稍后重试。'
  }
  return ''
}
export const traceStageLabels={capture:'采集登记',preprocess:'空景与有效帧',target_detection:'人车检测',semantic_analysis:'语义分析',module_projection:'分析模块',search_index:'检索投影',recording_binding:'录像关联',plate_review:'车牌复核'}
export const traceCheckLabels={original_image:'原始关键帧',inference_receipt:'模型识别回执',elasticsearch_document:'当前检索文档',recording_file:'录像文件'}
export const traceStateLabels={missing:'尚无记录',unavailable:'暂不可读',unverified:'缺少登记摘要',matched:'一致性校验通过',changed:'记录不一致',recorded:'已记录',not_started:'尚未开始',completed:'已完成',skipped:'本地结单，未调用模型',pending:'等待处理',submitted:'已提交',queued:'等待处理',active:'处理中',running:'处理中',leased:'处理中',lease_expired:'租约待回收',retry_wait:'等待重试',failed:'处理失败',dead:'处理失败',cancelled:'已取消',not_checked:'本次未核验'}

export async function verifiedTrace(value,expected){
  if(!value||value.schema_version!=='processing-trace-v1'||value.frame_id!==expected.frame||expected.observation&&(value.observation_id!==expected.observation||value.record_version!==expected.version)||!Array.isArray(value.checks)||!Array.isArray(value.stages)||value.checks.length>16||value.stages.length>32||typeof value.replay_manifest_json!=='string'||!digestPattern.test(value.replay_manifest_sha256||''))throw new Error('处理记录与当前线索不一致，请重新读取。')
  const bytes=new TextEncoder().encode(value.replay_manifest_json)
  if(bytes.length>512*1024||!globalThis.crypto?.subtle)throw new Error('当前环境无法校验处理记录。')
  const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),n=>n.toString(16).padStart(2,'0')).join('')
  if(hash!==value.replay_manifest_sha256)throw new Error('处理记录摘要校验失败，请重新读取。')
  const manifest=JSON.parse(value.replay_manifest_json)
  if(manifest.schema_version!=='processing-replay-v1'||manifest.frame_id!==expected.frame||manifest.camera_id!==value.camera_id||manifest.image_sha256!==null&&!digestPattern.test(manifest.image_sha256||''))throw new Error('还原记录与来源不一致。')
  return {...value,manifest}
}

export function traceError(status){return status===409?'来源或处理状态已经更新，请重新读取。':status===404?'当前记录尚无可用的处理追溯。':'处理记录暂不可用，请稍后重试。'}
