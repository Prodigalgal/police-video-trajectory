import {verifyEvidenceDigest} from './result-format.js'

const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=/^[a-f0-9]{64}$/
const errors={media_jobs_disabled:'录像制作暂未启用。',media_jobs_full:'制作队列已满，请稍后重试。',media_request_conflict:'提交内容已变化，请刷新详情。',media_revision_conflict:'任务状态已更新，请刷新后重试。',media_session_changed:'录像来源已变化，请重新打开详情。',media_session_unavailable:'当前帧的留存录像不可用。',media_frame_limit:'该片段关联帧已达上限，请联系管理员。',media_source_changed:'来源校验未通过，请联系管理员。',media_evidence_changed:'片段内容校验未通过，请联系管理员。',media_manifest_changed:'片段清单校验未通过，请重新读取。',media_job_unavailable:'该任务不可用。',media_unavailable:'分析片段暂不可用。'}
export const mediaPhases={paused:'排队中 · 处理已暂停',waiting:'等待制作',retry_wait:'等待自动重试',running:'正在制作',lease_expired:'等待租约恢复',completed:'已完成',failed:'制作失败'}
export const mediaFailure={media_source_changed:'来源已变化',media_source_unavailable:'来源暂不可读',media_input_invalid:'来源或时钟校验失败',media_capacity_reached:'存储配额已满',media_store_busy:'存储正在使用',media_worker_error:'制作进程异常',media_timeout:'制作超时',job_timeout:'任务超时',consumer_interrupted:'消费者已停止',result_commit_unavailable:'结果登记暂不可用'}

export async function mediaRequest(path,options={}){
  const signal=options.signal?AbortSignal.any([options.signal,AbortSignal.timeout(130000)]):AbortSignal.timeout(130000)
  let response
  try{response=await fetch('/api/v1/'+path,{...options,signal,cache:'no-store',headers:{'Content-Type':'application/json',...options.headers}})}
  catch(error){if(options.signal?.aborted)throw error;throw new Error('录像制作请求未能完成，请检查连接后重试。')}
  if(!response.ok){let value={};try{const parsed=await response.json();if(parsed&&typeof parsed==='object'&&!Array.isArray(parsed))value=parsed}catch{};throw Object.assign(new Error(response.status===401?'登录已失效，请重新登录。':errors[value.code]||'录像制作服务暂不可用，请稍后重试。'),{unauthorized:response.status===401,code:value.code})}
  return response
}
export async function mediaJson(path,options={}){return (await mediaRequest(path,options)).json()}
function invalid(){throw new Error('返回的片段记录不一致，请重新读取。')}
export function validateMediaJob(value,camera){
  if(!value||!uuid.test(value.job_id)||!uuid.test(value.source_id)||value.camera_id!==camera||!['archive','session'].includes(value.input_kind)
    ||!sha.test(value.input_sha256)||!Number.isSafeInteger(value.revision)||value.revision<1||!Number.isSafeInteger(value.attempts)||value.attempts<0
    ||!Number.isSafeInteger(value.start_ms)||!Number.isSafeInteger(value.end_ms)||value.start_ms<0||value.end_ms-value.start_ms<2000||value.end_ms-value.start_ms>30000
    ||!['queued','active','completed','failed'].includes(value.state)||!Object.hasOwn(mediaPhases,value.dispatch_state)||typeof value.claims_enabled!=='boolean')invalid()
  const phases={queued:['paused','waiting','retry_wait'],active:['running','lease_expired'],completed:['completed'],failed:['failed']}
  if(!phases[value.state].includes(value.dispatch_state)||value.state==='completed'&&(value.clip_id!==value.job_id||!sha.test(value.manifest_sha256)))invalid()
  return value
}
export function validateMediaPage(value,camera,after=null){
  if(!value||!Array.isArray(value.items)||value.items.length>50||typeof value.has_more!=='boolean'
    ||value.has_more&&(!value.items.length||!uuid.test(value.next_cursor)||value.next_cursor!==value.items.at(-1).job_id)||!value.has_more&&value.next_cursor!==null)invalid()
  let previous=after;for(const job of value.items){validateMediaJob(job,camera);if(previous&&job.job_id<=previous)invalid();previous=job.job_id}return value
}
export function validateMediaManifest(value,job){
  if(!value||value.clip_id!==job.job_id||value.source_id!==job.source_id||value.camera_id!==job.camera_id||value.requested_start_ms!==job.start_ms||value.requested_end_ms!==job.end_ms
    ||value.schema_version!==(job.input_kind==='session'?'recorded-session-clip-v1':'recorded-media-clip-v1')||value.quality?.inference_invoked!==false
    ||!Number.isSafeInteger(value.actual_start_us)||!Number.isSafeInteger(value.actual_end_us)||value.actual_end_us<=value.actual_start_us
    ||!Array.isArray(value.samples)||value.samples.length<2||value.samples.length>31||!sha.test(value.video?.sha256))invalid()
  if(job.input_kind==='session'&&(value.session_input?.source_id!==job.source_id||value.session_input?.camera_id!==job.camera_id||value.session_input?.version!=='session-segment-input-v1'))invalid()
  value.samples.forEach((sample,index)=>{if(sample.index!==index||!sha.test(sample.sha256)||!Number.isSafeInteger(sample.bytes)||sample.bytes<1||sample.bytes>8*1024**2
    ||!Number.isSafeInteger(sample.offset_ms)||sample.offset_ms<0||!Number.isSafeInteger(sample.source_pts_us)||sample.source_pts_us<value.actual_start_us||sample.source_pts_us>value.actual_end_us
    ||Math.abs(sample.source_pts_us-value.actual_start_us-sample.offset_ms*1000)>500)invalid()})
  return value
}
export async function checkedMediaBytes(path,expected,limit,signal){
  const response=await mediaRequest(path,{signal}),reader=response.body.getReader();let size=0;const chunks=[]
  try{while(true){const {value,done}=await reader.read();if(done)break;size+=value.length;if(size>limit)invalid();chunks.push(value)}}finally{await reader.cancel()}
  const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length}
  let verified;try{verified=await verifyEvidenceDigest(bytes.buffer,expected)}catch{throw new Error('片段内容摘要不一致，已停止显示。')}
  if(verified!=='valid')throw new Error('当前浏览器不能核验片段内容，请使用安全连接。')
  return bytes
}
