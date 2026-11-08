import {validateInput} from './analysis-input-client.js'
import {validateSpecialistJob} from './specialist-client.js'

const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
function invalid(){throw new Error('线索与专用任务的来源不一致，请重新打开详情。')}
function instant(value){
  if(typeof value!=='string')invalid()
  const parts=/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value)
  const seconds=parts&&Date.parse(parts[1]+'Z')
  if(!parts||!Number.isFinite(seconds)||new Date(seconds).toISOString().slice(0,19)!==parts[1])invalid()
  return BigInt(seconds)*1000000n+BigInt((parts[2]||'').padEnd(9,'0'))
}

export function specialistCursor(entry){return {job_id:entry.job.job_id,created_at:entry.job.created_at}}

export function validateObservationSpecialists(value,detail,after=null,limit=10){
  const anchor=value?.anchor
  if(value?.schema_version!=='observation-specialists-v1'||anchor?.observation_id!==detail.observation_id||anchor.frame_id!==detail.frame_id
      ||anchor.camera_id!==detail.camera_id||anchor.record_version!==detail.record_version||!Array.isArray(value.items)||value.items.length>limit
      ||typeof value.has_more!=='boolean'||(value.has_more?(value.items.length!==limit||value.next_cursor!==value.items.at(-1)?.job?.job_id):value.next_cursor!==null))invalid()
  let previous=after
  const ids=new Set()
  for(const entry of value.items){
    if(!entry?.input||!entry.job)invalid()
    const input=validateInput(entry.input,detail.observation_id),job=validateSpecialistJob(entry.job,input)
    if(input.purpose!=='single_frame'||input.anchor_frame_id!==detail.frame_id||input.camera_id!==detail.camera_id
        ||input.anchor_record_version>detail.record_version||input.source_status!==(input.anchor_record_version===detail.record_version?'not_revalidated':'stale')
        ||!uuid.test(job.job_id)||ids.has(job.job_id))invalid()
    const timestamp=instant(job.created_at)
    if(previous){const boundary=instant(previous.created_at);if(timestamp>boundary||timestamp===boundary&&job.job_id>=previous.job_id)invalid()}
    ids.add(job.job_id);previous=specialistCursor(entry)
  }
  return value
}
