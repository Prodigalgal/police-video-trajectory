import test from 'node:test'
import assert from 'node:assert/strict'
import {validateSpecialistJob,validateSpecialistPage,validateSpecialistResult,readSpecialistResult,validateSpecialistSelection,selectionReason} from '../src/specialist-client.js'
import {createHash} from 'node:crypto'
const input={input_id:'11111111-1111-4111-8111-111111111111',anchor_frame_id:'22222222-2222-4222-8222-222222222222',camera_id:'recording-01',manifest_sha256:'a'.repeat(64)}
function job(){return {job_id:'33333333-3333-4333-8333-333333333333',input_id:input.input_id,frame_id:input.anchor_frame_id,camera_id:input.camera_id,input_manifest_sha256:input.manifest_sha256,input_sha256:'b'.repeat(64),operation:'license_plate',profile:'protocol-test-v1',state:'completed',dispatch_state:'completed',revision:3,attempts:0,result_sha256:'c'.repeat(64)}}

test('specialist selection is source bound and never claims identity or completed inference',()=>{
  const value={selection_required:true,decision:'selected',reasons:['usable_vehicle_candidate_requires_plate_localization'],modules:[{module:'license_plate',decision:'selected',reasons:['first_usable_candidate']}],frame_id:input.anchor_frame_id,policy_sha256:'d'.repeat(64),jpeg_sha256:'e'.repeat(64),selected_target_ids:['44444444-4444-4444-8444-444444444444'],identity_association_verified:false,recommendation_is_inference_completion:false}
  assert.equal(validateSpecialistSelection(value,input),value)
  for(const patch of [{frame_id:job().job_id},{identity_association_verified:true},{recommendation_is_inference_completion:true},{selected_target_ids:[]}])assert.throws(()=>validateSpecialistSelection({...value,...patch},input))
})

test('deferred unavailable and skipped selection keep distinct bounded reasons',()=>{
  for(const decision of ['deferred','unavailable','skipped'])assert.equal(validateSpecialistSelection({selection_required:true,decision,reasons:['selection_evidence_missing'],modules:[]},input).decision,decision)
  assert.throws(()=>validateSpecialistSelection({selection_required:true,decision:'selected',reasons:['<script>'],modules:[]},input))
  assert.equal(selectionReason('unknown_reason'),'当前画面暂不满足该模块的选择条件')
})
test('reuse is an original result reference rather than plate assignment',()=>{
  const value={selection_required:true,decision:'reused',business_state:'reuse_result',reasons:['confirmed_event_result_available'],modules:[],frame_id:input.anchor_frame_id,reuse_reference:{job_id:job().job_id,frame_id:input.anchor_frame_id,input_id:input.input_id,input_manifest_sha256:input.manifest_sha256,profile:'plate-a',revision:1}}
  assert.equal(validateSpecialistSelection(value,input),value)
  assert.throws(()=>validateSpecialistSelection({...value,reuse_reference:{...value.reuse_reference,frame_id:'wrong'}},input))
  assert.throws(()=>validateSpecialistSelection({...value,business_state:'need_read'},input))
})
test('specialist pagination, input scope, and completed state are validated',()=>{
  const current=job();assert.equal(validateSpecialistJob(current,input),current)
  for(const changed of [{frame_id:current.job_id},{camera_id:'recording-02'},{dispatch_state:'paused'},{revision:0}])assert.throws(()=>validateSpecialistJob({...current,...changed},input))
  assert.throws(()=>validateSpecialistPage({items:[current],has_more:true,next_cursor:current.job_id},input,current.job_id))
})
test('unreadable remains unreviewed auxiliary evidence; identity and invalid geometry are refused',()=>{
  const current=job(),result={schema_version:'specialist-result-v1',job_id:current.job_id,input_sha256:current.input_sha256,frame_id:current.frame_id,evidence_sha256:'d'.repeat(64),operation:current.operation,profile:current.profile,image_width:1280,image_height:960,engine:{name:'protocol-fixture',version:'not-accuracy-evidence'},outcome:'unreadable',regions:[],limitations:[]},value={job_id:current.job_id,result_revision:1,result_sha256:current.result_sha256,review_state:'unreviewed',search_indexed:false,score_calibrated:false,engine_provenance:'provider_declared',result}
  assert.equal(validateSpecialistResult(value,current),value)
  assert.throws(()=>validateSpecialistResult({...value,review_state:'identity_confirmed'},current))
  assert.throws(()=>validateSpecialistResult({...value,result:{...result,outcome:'recognized'}},current))
  assert.throws(()=>validateSpecialistResult({...value,result:{...result,outcome:'recognized',regions:[{index:0,text:'协议字段',bbox_xyxy:[0,0,2000,10],score:.99}]}},current))
})
test('the exact stored result payload must match the job SHA before display',async()=>{
  const current=job(),result={schema_version:'specialist-result-v1',job_id:current.job_id,input_sha256:current.input_sha256,frame_id:current.frame_id,evidence_sha256:'d'.repeat(64),operation:current.operation,profile:current.profile,image_width:1280,image_height:960,engine:{name:'protocol-fixture',version:'transport-only'},outcome:'unreadable',regions:[],limitations:[]}
  const payload=JSON.stringify(result);current.result_sha256=createHash('sha256').update(payload).digest('hex');const value={job_id:current.job_id,result_revision:1,result_sha256:current.result_sha256,review_state:'unreviewed',score_calibrated:false,search_indexed:false,engine_provenance:'provider_declared',result_payload:payload}
  assert.equal((await readSpecialistResult(value,current)).result.outcome,'unreadable')
  await assert.rejects(readSpecialistResult({...value,result_payload:payload.replace('unreadable','no_text')},current),/摘要不一致/)
})
test('plate review metadata cannot invent a confirmed region or revision',()=>{
  const current=job(),result={schema_version:'specialist-result-v1',job_id:current.job_id,input_sha256:current.input_sha256,frame_id:current.frame_id,evidence_sha256:'d'.repeat(64),operation:current.operation,profile:current.profile,image_width:1280,image_height:960,engine:{name:'review-state-contract',version:'not-recognition'},outcome:'recognized',regions:[{index:0,text:'状态契约夹具',bbox_xyxy:[0,0,100,20],score:null}],limitations:[]}
  const value={job_id:current.job_id,result_revision:1,result_sha256:current.result_sha256,review_state:'unreviewed',score_calibrated:false,search_indexed:false,engine_provenance:'provider_declared',result,plate_review_available:true,plate_review:{state:'confirmed',revision:1,region_index:0}}
  assert.equal(validateSpecialistResult(value,current),value)
  for(const review of [{state:'confirmed',revision:0,region_index:0},{state:'confirmed',revision:1,region_index:2},{state:'unreviewed',revision:1},{state:'identity_confirmed',revision:1,region_index:0}])assert.throws(()=>validateSpecialistResult({...value,plate_review:review},current))
})
