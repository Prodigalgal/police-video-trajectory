const phaseNames={queued:'等待领取',active:'处理中',completed:'已完成',failed:'失败，需处理',
  retry_wait:'等待重试',lease_expired:'租约过期，等待回收',inconsistent:'状态异常，需核查'}
const failureNames={model_unavailable:'模型服务暂不可用',model_timeout:'模型请求超时（360 秒）',
  model_rate_limited:'模型服务限流',job_timeout:'单次任务超时',consumer_interrupted:'消费者退出',
  invalid_model_result:'模型结果未通过校验',evidence_unavailable:'证据文件不可用',
  result_commit_unavailable:'结果提交未获确认',lease_expired:'消费者租约过期',retry_exhausted:'重试次数已耗尽',
  invalid_module_source:'保存结果的完整性校验失败',module_unavailable:'模块处理暂不可用'}
export const moduleNames={person_attributes:'人物属性',vehicle_attributes:'车辆属性',scene_context:'场景上下文'}

export function taskPhase(job,paused=false) {
  const phase=job?.scheduling_state||job?.state
  const label=phaseNames[phase]||'状态未知'
  return {phase,label:paused&&['queued','retry_wait'].includes(phase)?label+'（已暂停）':label}
}

export function taskFailure(code) {
  return code?failureNames[code]||'未分类失败':'—'
}
