const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=/^[a-f0-9]{64}$/
const count=(value,min,max)=>Number.isSafeInteger(value)&&value>=min&&value<=max
const time=value=>typeof value==='string'&&value.length<=80&&Number.isFinite(Date.parse(value))
export const packageIntegrityNames={matched:'与采集登记一致',legacy_without_digest:'缺少采集登记摘要'}
export function imagePackageContext(collection,part){
  if(!uuid.test(collection?.collection_id||'')||!count(collection.revision,1,Number.MAX_SAFE_INTEGER)||!count(part,1,13))throw new Error('清单或分卷无效，请刷新后重试。')
  return {id:collection.collection_id,revision:collection.revision,part,key:[collection.collection_id,collection.revision,part].join(':')}
}
export function imagePackagePreviewPath(context){return `/api/v1/collections/${context.id}/package-preview?revision=${context.revision}&part=${context.part}`}
export function validateImagePackagePreview(value,context){
  const invalid=()=>{throw new Error('分卷清单未通过校验，请重新核验。')}
  if(!value||value.schema_version!=='evidence-package-preview-v1'||value.collection_id!==context.id||value.collection_revision!==context.revision||value.part!==context.part||value.frames_per_part!==16||!count(value.frame_count,0,200)||value.parts!==Math.max(1,Math.ceil(value.frame_count/16))||value.part>value.parts||!sha.test(value.source_sha256||'')||!time(value.prepared_at)||!Array.isArray(value.frames)||value.frames.length!==Math.min(16,Math.max(0,value.frame_count-(value.part-1)*16)))invalid()
  const frames=new Set(),observations=new Set();let total=0
  for(const frame of value.frames){
    if(!frame||!uuid.test(frame.frame_id||'')||frames.has(frame.frame_id)||!count(frame.bytes,1,8*1024**2)||!sha.test(frame.sha256||'')||typeof frame.camera_id!=='string'||!/^[A-Za-z0-9_.-]{1,100}$/.test(frame.camera_id)||typeof frame.camera_name!=='string'||!frame.camera_name||frame.camera_name.length>160||!Object.hasOwn(packageIntegrityNames,frame.capture_integrity)||!Array.isArray(frame.observations)||!frame.observations.length||frame.observations.length>200)invalid()
    if(frame.source_time!==null&&!time(frame.source_time)||frame.received_at!==null&&!time(frame.received_at)||frame.source_time_origin!==null&&(typeof frame.source_time_origin!=='string'||frame.source_time_origin.length>80))invalid()
    frames.add(frame.frame_id);total+=frame.bytes
    for(const observation of frame.observations){
      if(!observation||!uuid.test(observation.observation_id||'')||observations.has(observation.observation_id)||!count(observation.record_version,1,Number.MAX_SAFE_INTEGER)||!['person','vehicle'].includes(observation.target_type)||!['pending','retained','excluded'].includes(observation.review_state)||typeof observation.review_stale!=='boolean')invalid()
      observations.add(observation.observation_id)
    }
  }
  const reason=!frames.size?'empty_package':total>64*1024**2?'package_size_limit':null
  if(observations.size>200||value.image_bytes!==total||value.download_allowed!==(reason===null)||value.download_reason!==reason)invalid()
  return value
}
export function imagePackageDownloadPath(preview,context){
  validateImagePackagePreview(preview,context)
  if(!preview.download_allowed)throw new Error(preview.download_reason==='empty_package'?'本卷暂无可导出的原图。':'本卷原图超过容量限制，请拆分线索集。')
  return `/api/v1/collections/${context.id}/package?`+new URLSearchParams({revision:String(context.revision),part:String(context.part),expected_source_sha256:preview.source_sha256})
}
export function packageReviewLabel(observation){return observation.review_stale?'原复核已过期':({pending:'待复核',retained:'保留线索',excluded:'已排除'}[observation.review_state]||'待核对')}
export function imagePackageSize(bytes){return `${(bytes/1024**2).toFixed(2)} 兆字节`}
