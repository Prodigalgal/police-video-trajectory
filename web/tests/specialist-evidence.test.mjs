import test from 'node:test'
import assert from 'node:assert/strict'
import {boxOverlap,regionCrop,validateSpecialistEvidence,canReviewRegion,evidenceKey} from '../src/specialist-evidence.js'
const id='11111111-1111-4111-8111-111111111111',frame='22222222-2222-4222-8222-222222222222',input='33333333-3333-4333-8333-333333333333',obs='44444444-4444-4444-8444-444444444444'
const job={job_id:id,frame_id:frame,input_id:input,camera_id:'camera-01',result_sha256:'a'.repeat(64),input_manifest_sha256:'b'.repeat(64)}
const artifact=()=>({job_id:id,result_sha256:job.result_sha256,plate_review_available:true,result:{frame_id:frame,evidence_sha256:'c'.repeat(64),operation:'license_plate',outcome:'recognized',image_width:1000,image_height:600,regions:[{index:0,bbox_xyxy:[100,200,200,240]},{index:1,bbox_xyxy:[700,200,800,240]}]},result_source:{schema_version:'specialist-result-source-v1',job_id:id,frame_id:frame,input_id:input,camera_id:'camera-01',result_sha256:job.result_sha256,input_manifest_sha256:job.input_manifest_sha256,observation_id:obs,record_version:1,target_type:'vehicle'},plate_review_target:{schema_version:'plate-review-target-v1',job_id:id,frame_id:frame,result_sha256:job.result_sha256,evidence_sha256:'c'.repeat(64),plate_bbox_xyxy:[100,200,200,240],eligible_region_indices:[0]}})
test('原始出处与牌面归属必须绑定同一任务、图像、清单及结果',()=>{
  assert.equal(validateSpecialistEvidence(artifact(),job).result_source.observation_id,obs)
  for(const mutate of [v=>{v.result_source.frame_id=id},v=>{v.result_source.input_manifest_sha256='d'.repeat(64)},v=>{v.result_source.record_version=0},v=>{v.plate_review_target.eligible_region_indices=[1]},v=>{v.plate_review_target.result_sha256='d'.repeat(64)},v=>{v.plate_review_target.plate_bbox_xyxy=[-1,0,50,50]}]){const value=artifact();mutate(value);assert.throws(()=>validateSpecialistEvidence(value,job),/不一致/)}
  const old=artifact();delete old.result_source;delete old.plate_review_target;assert.equal(validateSpecialistEvidence(old,job),old);assert.equal(canReviewRegion(old,0,true),false)
})
test('放大窗口保留完整原生区域且不越界，未显示或异区不能确认',()=>{
  assert.equal(boxOverlap([0,0,100,20],[0,0,50,20]),.5)
  assert.deepEqual(regionCrop([0,0,100,20],1000,600),[0,0,112,32])
  assert.deepEqual(regionCrop([950,580,1000,600],1000,600),[938,568,62,32])
  assert.throws(()=>regionCrop([0,0,NaN,10],1000,600))
  assert.equal(canReviewRegion(artifact(),0,true),true);assert.equal(canReviewRegion(artifact(),0,false),false);assert.equal(canReviewRegion(artifact(),1,true),false)
  assert.notEqual(evidenceKey(job,artifact()),evidenceKey({...job,job_id:obs},{...artifact(),job_id:obs}))
})
