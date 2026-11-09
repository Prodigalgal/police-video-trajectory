import {mediaJson,mediaPhases} from './media-job-client.js'
import {verifyEvidenceDigest} from './result-format.js'
import {validateSpecialistEvidence} from './specialist-evidence.js'
export const operationNames={text_ocr:'画面文字识别',license_plate:'车牌识别'}
export const outcomeNames={recognized:'识别到文字',no_text:'未检出文字',unreadable:'画面不可读'}
export const limitationNames={low_resolution:'分辨率不足',blur:'画面模糊',occlusion:'遮挡',unsupported_language:'不支持的文字类型',uncalibrated_score:'模型分值未校准',uncertain_characters:'字符不确定',partial_text:'文字不完整',inconsistent_readings:'多次读号不一致'}
export const specialistFailures={specialist_unavailable:'专用服务暂不可用',specialist_timeout:'专用服务超时',specialist_input_changed:'来源输入已变化',invalid_specialist_result:'结果未通过契约校验',job_timeout:'任务超时',consumer_interrupted:'消费者已停止',result_commit_unavailable:'结果登记暂不可用'}
const messages={specialist_not_configured:'专用识别服务尚未配置，未创建任务。',specialist_disabled:'专用识别尚未启用。',specialist_queue_full:'专用队列已满，请稍后重试。',specialist_request_conflict:'本次请求内容已变化，请重新打开输入。',specialist_revision_conflict:'任务状态已有更新，请刷新后重试。',specialist_input_changed:'来源输入已变化，请重新保存单帧。',input_source_changed:'来源记录已变化，请重新保存单帧。',input_manifest_changed:'输入清单校验失败，请重新读取。',specialist_result_changed:'结果校验未通过，已停止显示。',specialist_result_unavailable:'识别结果尚不可用。',specialist_single_frame_required:'当前仅支持已保存的单帧输入。'}
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=/^[a-f0-9]{64}$/
Object.assign(messages,{specialist_not_selected:'本帧未被选择用于车牌识别，请查看选择原因。',specialist_selection_unavailable:'选择依据暂不可用，未创建任务。',specialist_selection_budget:'该点位的专用分析预算暂满，请稍后重试。',specialist_module_disabled:'当前识别模块已停用，已有任务仍可查看。',processing_catalog_unavailable:'模型与模块配置暂不可用，已有任务保持原绑定。',processing_profile_conflict:'模型版本定义冲突，未切换或创建任务。'})
export const selectionNames={selected:'建议尝试车牌识别',skipped:'本帧未选择',deferred:'等待更合适画面',unavailable:'选择依据暂不可用',reused:'已有确认结果可引用'}
export const businessStateNames={not_applicable:'当前不需要识别',waiting_evidence:'等待更好的牌面证据',need_read:'需要提交车牌识别',waiting_result:'已有任务或候选，等待处理',pending_review:'已有候选读号，等待复核',reuse_result:'可引用已有确认结果',budget_wait:'处理预算暂满，保留候选等待',unreadable:'已达到补读次数，尚未可靠读出'}
Object.assign(messages,{specialist_result_reusable:'已有确认结果可引用，无需创建重复任务。',invalid_plate_review:'请核对原始画面和完整牌号后再确认。',plate_review_conflict:'复核记录已更新，请重新读取结果。',plate_review_unavailable:'该结果没有可核验的车辆与牌面依据。',plate_review_target_mismatch:'所选文字区域与本次候选牌面不对应，请重新选择。'})
const selectionReasons={requested_vehicle_check:'已申请核验当前车辆，仍需检测可读牌面',requested_vehicle_required:'主动核验仅适用于车辆',requested_vehicle_too_small:'车辆区域过小或缺少可靠位置，请选择更近画面',selection_evidence_missing:'尚无专用分析选择记录',selection_policy_unavailable:'选择策略尚未就绪',selection_not_applied:'选择策略仅观察或已停用',selection_evidence_invalid:'选择记录未通过来源校验',detection_incomplete:'目标检测信息不完整',no_detected_vehicle_candidate:'未检出对应车辆候选',no_new_specialist_candidate:'近期没有更合适的新候选',wait_for_usable_vehicle_candidate:'等待像素或清晰度更合适的车辆画面',usable_vehicle_candidate_requires_plate_localization:'车辆画面可供尝试，仍需确认车牌位置与可读性',face_detection_evidence_required:'需要真实脸部检测依据，整个人形框不足以判定',no_detected_person_candidate:'未检出对应人形候选'}
export function selectionReason(value){return selectionReasons[value]||'当前画面暂不满足该模块的选择条件'}
Object.assign(selectionReasons,{readable_plate_candidate:'已检测到可供尝试读取的牌面',camera_budget_wait:'该点位预算暂满，原始候选仍保留',event_job_pending:'该候选片段已有任务处理中',confirmed_event_result_available:'该连续候选片段已有人工确认的结果',event_attempts_exhausted:'已达到该候选片段的补读上限',event_result_awaiting_review:'已有模型读号，尚未人工确认'})
Object.assign(selectionReasons,{stationary_without_passage_event:'当前为静止停放，未见对应移动片段',passage_evidence_required:'尚缺少对应车辆的移动证据',plate_probe_unavailable:'牌面定位暂不可用，等待证据',plate_not_visible:'尚未定位到对应牌面',plate_association_ambiguous:'牌面与车辆的空间对应存在歧义',wait_for_larger_plate_image:'牌面原生像素不足，等待更近画面',wait_for_better_plate_image:'牌面质量不足，等待更清晰画面',quality_unavailable:'牌面质量信息暂不可用',association_unavailable:'车辆局部关联依据暂不可用',plate_profile_class_unsupported:'当前目标类别不适用此车牌配置',first_usable_candidate:'首次获得可供尝试读取的牌面',better_readability_candidate:'出现质量更好的牌面候选',recent_recommendation_not_improved:'近期已有更好的候选，当前不重复推荐',recommendation_cooldown:'候选推荐冷却中',event_recommendation_limit:'本片段候选推荐已达上限，等待实际任务结果',frame_target_budget:'本帧已优先选择其他牌面候选'})
export function validateSpecialistSelection(value,input){
  if(!value||typeof value.selection_required!=='boolean'||!Object.hasOwn(selectionNames,value.decision)||!Array.isArray(value.reasons)||!value.reasons.length||value.reasons.length>12||value.reasons.some(v=>typeof v!=='string'||!/^[a-z_]{1,80}$/.test(v))||!Array.isArray(value.modules)||value.modules.length>2)invalid()
  if(value.frame_id!==undefined&&value.frame_id!==input.anchor_frame_id)invalid()
  if(value.business_state!==undefined&&!Object.hasOwn(businessStateNames,value.business_state))invalid()
  if(value.decision==='reused'){
    const reference=value.reuse_reference
    if(value.business_state!=='reuse_result'||!reference||!uuid.test(reference.job_id)||!uuid.test(reference.frame_id)||!uuid.test(reference.input_id)||!sha.test(reference.input_manifest_sha256)||typeof reference.profile!=='string'||!/^[A-Za-z0-9_.-]{1,80}$/.test(reference.profile)||!Number.isSafeInteger(reference.revision)||reference.revision<1)invalid()
  }
   const selectedIds=value.selection_mode==='requested'?value.selected_observation_ids:value.selected_target_ids
   if(value.decision==='selected'&&(!sha.test(value.policy_sha256)||!sha.test(value.jpeg_sha256)||!Array.isArray(selectedIds)||selectedIds.length!==1||selectedIds.some(v=>!uuid.test(v))||value.identity_association_verified!==false||value.recommendation_is_inference_completion!==false))invalid()
  const kinds=new Set()
  for(const module of value.modules){if(!['license_plate','face_quality'].includes(module.module)||kinds.has(module.module)||!['selected','skipped','deferred'].includes(module.decision)||!Array.isArray(module.reasons)||!module.reasons.length||module.reasons.some(v=>typeof v!=='string'||!/^[a-z_]{1,80}$/.test(v)))invalid();kinds.add(module.module)}
  return value
}
Object.assign(messages,{invalid_specialist_cursor:'专用任务翻页会话已变化，请重新打开详情。'})
function invalid(){throw new Error('专用识别记录不一致，请重新读取。')}
export async function specialistApi(path,options={}){try{return await mediaJson(path,options)}catch(error){if(messages[error.code])error.message=messages[error.code];throw error}}
export function validateSpecialistPage(value,input,after=null){
  if(!value||!Array.isArray(value.items)||value.items.length>50||typeof value.has_more!=='boolean'||value.has_more&&(!value.items.length||value.next_cursor!==value.items.at(-1).job_id)||!value.has_more&&value.next_cursor!==null)invalid()
  let previous=after
  for(const job of value.items){validateSpecialistJob(job,input);if(previous&&job.job_id<=previous)invalid();previous=job.job_id}return value
}
export function validateSpecialistJob(value,input){
  if(!value||!uuid.test(value.job_id)||value.input_id!==input.input_id||value.frame_id!==input.anchor_frame_id||value.camera_id!==input.camera_id||value.input_manifest_sha256!==input.manifest_sha256||!sha.test(value.input_sha256)||!Object.hasOwn(operationNames,value.operation)||!Object.hasOwn(mediaPhases,value.dispatch_state)||!['queued','active','completed','failed'].includes(value.state)||!Number.isSafeInteger(value.revision)||value.revision<1||!Number.isSafeInteger(value.attempts)||value.attempts<0||typeof value.profile!=='string'||!/^[A-Za-z0-9_.-]{1,80}$/.test(value.profile))invalid()
  if(!{queued:['paused','waiting','retry_wait'],active:['running','lease_expired'],completed:['completed'],failed:['failed']}[value.state].includes(value.dispatch_state)||value.state==='completed'&&!sha.test(value.result_sha256))invalid();return value
}
export function validateSpecialistResult(value,job){
  const result=value?.result
  if(value?.job_id!==job.job_id||value.result_revision!==1||value.result_sha256!==job.result_sha256||value.review_state!=='unreviewed'||value.search_indexed!==false||value.score_calibrated!==false||value.engine_provenance!=='provider_declared'||result?.schema_version!=='specialist-result-v1'||result.job_id!==job.job_id||result.input_sha256!==job.input_sha256||result.frame_id!==job.frame_id||result.operation!==job.operation||result.profile!==job.profile||!sha.test(result.evidence_sha256)||!Object.hasOwn(outcomeNames,result.outcome)||!Array.isArray(result.regions)||result.regions.length>100||(result.outcome==='recognized')!==(result.regions.length>0)||!Number.isSafeInteger(result.image_width)||!Number.isSafeInteger(result.image_height)||result.image_width<1||result.image_height<1||result.image_width>20000||result.image_height>20000)invalid()
  if(!result.engine||Object.keys(result.engine).length!==2||typeof result.engine.name!=='string'||typeof result.engine.version!=='string'||!result.engine.name||!result.engine.version)invalid()
  if(!Array.isArray(result.limitations)||result.limitations.length>8||new Set(result.limitations).size!==result.limitations.length||result.limitations.some(value=>!Object.hasOwn(limitationNames,value))||result.operation==='license_plate'&&result.outcome==='recognized'&&result.limitations.some(value=>['uncertain_characters','partial_text','inconsistent_readings'].includes(value)))invalid()
  result.regions.forEach((region,index)=>{const box=region.bbox_xyxy;if(region.index!==index||typeof region.text!=='string'||!region.text.trim()||region.text.length>(result.operation==='license_plate'?64:512)||!Array.isArray(box)||box.length!==4||box.some(n=>!Number.isSafeInteger(n))||box[0]<0||box[1]<0||box[2]<=box[0]||box[3]<=box[1]||box[2]>result.image_width||box[3]>result.image_height||region.score!==null&&(typeof region.score!=='number'||!Number.isFinite(region.score)||region.score<0||region.score>1))invalid()})
  if(value.plate_review_available!==undefined&&typeof value.plate_review_available!=='boolean')invalid()
  if(value.plate_review!==undefined){
    const review=value.plate_review
    if(result.operation!=='license_plate'||result.outcome!=='recognized'||!review||!['unreviewed','confirmed','revoked'].includes(review.state)||!Number.isSafeInteger(review.revision)||review.revision<0)invalid()
    if(review.state==='unreviewed'?review.revision!==0:review.revision<1||!Number.isSafeInteger(review.region_index)||review.region_index<0||review.region_index>=result.regions.length)invalid()
  }
  if(value.plate_review_available&&!value.plate_review)invalid()
  return validateSpecialistEvidence(value,job)
}
export async function readSpecialistResult(value,job){
  if(typeof value?.result_payload!=='string')invalid()
  const bytes=new TextEncoder().encode(value.result_payload)
  if(bytes.length>128*1024)invalid()
  let verified;try{verified=await verifyEvidenceDigest(bytes.buffer,job.result_sha256)}catch{throw new Error('识别结果摘要不一致，已停止显示。')}
  if(verified!=='valid')invalid()
  return validateSpecialistResult({...value,result:JSON.parse(value.result_payload)},job)
}
