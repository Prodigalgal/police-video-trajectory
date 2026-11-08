const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const sha=/^[a-f0-9]{64}$/
const invalid=()=>{throw new Error('识别结果的区域或来源记录不一致，请重新读取。')}

export function validRegionBox(box,width,height){
  return Array.isArray(box)&&box.length===4&&box.every(Number.isSafeInteger)&&Number.isSafeInteger(width)&&Number.isSafeInteger(height)&&width>0&&height>0&&box[0]>=0&&box[1]>=0&&box[2]<=width&&box[3]<=height&&box[0]<box[2]&&box[1]<box[3]
}
export function boxOverlap(a,b){
  const intersection=Math.max(0,Math.min(a[2],b[2])-Math.max(a[0],b[0]))*Math.max(0,Math.min(a[3],b[3])-Math.max(a[1],b[1]))
  const union=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-intersection
  return union>0?intersection/union:0
}
export function validateSpecialistEvidence(artifact,job){
  const result=artifact.result,source=artifact.result_source,target=artifact.plate_review_target
  if(source!==undefined){
    if(source?.schema_version!=='specialist-result-source-v1'||source.job_id!==job.job_id||source.input_id!==job.input_id||source.frame_id!==job.frame_id||source.camera_id!==job.camera_id||source.result_sha256!==job.result_sha256||source.input_manifest_sha256!==job.input_manifest_sha256||!uuid.test(source.observation_id)||!Number.isSafeInteger(source.record_version)||source.record_version<1||!['person','vehicle'].includes(source.target_type))invalid()
  }
  if(target!==undefined){
    if(result.operation!=='license_plate'||result.outcome!=='recognized'||artifact.plate_review_available!==true||target?.schema_version!=='plate-review-target-v1'||target.job_id!==job.job_id||target.result_sha256!==job.result_sha256||target.frame_id!==job.frame_id||target.evidence_sha256!==result.evidence_sha256||!validRegionBox(target.plate_bbox_xyxy,result.image_width,result.image_height)||!Array.isArray(target.eligible_region_indices))invalid()
    const expected=result.regions.filter(region=>boxOverlap(target.plate_bbox_xyxy,region.bbox_xyxy)>=.5).map(region=>region.index)
    if(JSON.stringify(expected)!==JSON.stringify(target.eligible_region_indices))invalid()
  }
  return artifact
}
export function evidenceKey(job,artifact){
  const result=artifact?.result
  if(!result||job.job_id!==artifact.job_id||job.frame_id!==result.frame_id||job.result_sha256!==artifact.result_sha256||!sha.test(result.evidence_sha256))invalid()
  return [job.job_id,artifact.result_sha256,result.frame_id,result.evidence_sha256,result.image_width,result.image_height].join(':')
}
export function regionCrop(box,width,height,padding=12){
  if(!validRegionBox(box,width,height)||!Number.isSafeInteger(padding)||padding<0||padding>64)invalid()
  const left=Math.max(0,box[0]-padding),top=Math.max(0,box[1]-padding)
  return [left,top,Math.min(width,box[2]+padding)-left,Math.min(height,box[3]+padding)-top]
}
export function canReviewRegion(artifact,region,sourceReady){
  return sourceReady===true&&artifact?.plate_review_available===true&&artifact?.result?.operation==='license_plate'&&artifact.result.outcome==='recognized'&&Number.isSafeInteger(region)&&artifact.plate_review_target?.eligible_region_indices?.includes(region)===true
}
