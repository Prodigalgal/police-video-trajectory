import test from 'node:test'
import assert from 'node:assert/strict'
import {webcrypto} from 'node:crypto'
import {validateContext,validateInputPage,previewFrame,downloadInput} from '../src/analysis-input-client.js'

const obs='11111111-1111-4111-8111-111111111111',first='22222222-2222-4222-8222-222222222222',next='33333333-3333-4333-8333-333333333333',task='44444444-4444-4444-8444-444444444444'
const detail={observation_id:obs,frame_id:first,record_version:1,camera_id:'recording-01',source_type:'rtsp'}
const window={before_ms:60000,after_ms:60000,max_frames:16,max_gap_ms:6000}
function fixture(){return {anchor:{observation_id:obs,frame_id:first,record_version:1},window,frames:[first,next].map((id,index)=>({frame_id:id,task_id:task,scene_run_id:task,media_epoch:0,relative_media_ms:index*5000,camera_id:'recording-01',source_type:'rtsp',source_time_origin:'unknown',source_time:null,source_fingerprint:'a'.repeat(64),registered_payload_sha256:'b'.repeat(64),ambiguous_time:false,anchor_segment:true})),sequence:{eligible:true,reason:'sampled_sequence',frame_ids:[first,next]},truncated:false,quality:{continuous_video_verified:false,identity_association_verified:false}}}

test('同段采样序列通过，跨任务/会话/代次/点位和错误版本拒绝',()=>{
  assert.equal(validateContext(fixture(),detail,window).sequence.eligible,true)
  for(const [field,value] of [['task_id',next],['scene_run_id',next],['media_epoch',1],['camera_id','recording-02']]){const sample=fixture();sample.frames[1][field]=value;assert.throws(()=>validateContext(sample,detail,window),/不一致/)}
  const stale=fixture();stale.anchor.record_version=2;assert.throws(()=>validateContext(stale,detail,window),/不一致/)
})
test('服务端不能将稀疏帧、重复时间或逆序包装成合格序列',()=>{
  for(const time of [60000,0,-1]){const sample=fixture();sample.frames[1].relative_media_ms=time;assert.throws(()=>validateContext(sample,detail,window),/不一致/)}
  const ambiguous=fixture();ambiguous.frames[0].ambiguous_time=true;assert.throws(()=>validateContext(ambiguous,detail,window),/不一致/)
  const video=fixture();video.quality.continuous_video_verified=true;assert.throws(()=>validateContext(video,detail,window),/不一致/)
})
test('缺失时钟仅允许单帧，不借用记录时间',()=>{
  const value=fixture();value.frames=value.frames.slice(0,1);Object.assign(value.frames[0],{scene_run_id:null,media_epoch:null,relative_media_ms:null});value.sequence={eligible:false,reason:'clock_unavailable',frame_ids:[first]}
  assert.equal(validateContext(value,detail,window).sequence.eligible,false)
  value.frames[0].source_time='2026-01-01T00:00:00Z';assert.throws(()=>validateContext(value,detail,window))
})
test('分页必须属于当前观测且游标和条目唯一',()=>{
  const input={input_id:task,anchor_observation_id:obs,anchor_record_version:1,anchor_frame_id:first,purpose:'single_frame',manifest_sha256:'a'.repeat(64),source_status:'not_revalidated',creation_invoked_inference:false}
  assert.equal(validateInputPage({items:[input],has_more:false,next_cursor:null},obs).items.length,1)
  assert.throws(()=>validateInputPage({items:[input,input],has_more:false,next_cursor:null},obs),/重复/)
  assert.throws(()=>validateInputPage({items:[input],has_more:true,next_cursor:null},obs),/分页/)
  assert.throws(()=>validateInputPage({items:[input],has_more:false,next_cursor:null},next),/不一致/)
})
test('预览与导出都校验实际字节，摘要不一致拒绝',async t=>{
  const oldFetch=globalThis.fetch,oldCrypto=globalThis.crypto;t.after(()=>{globalThis.fetch=oldFetch;Object.defineProperty(globalThis,'crypto',{value:oldCrypto,configurable:true})});Object.defineProperty(globalThis,'crypto',{value:webcrypto,configurable:true})
  globalThis.fetch=async()=>new Response(new Uint8Array([1,2,3]))
  await assert.rejects(previewFrame({frame_id:first,registered_payload_sha256:'a'.repeat(64)}),/不一致/)
  await assert.rejects(previewFrame({frame_id:first,registered_payload_sha256:null}),/缺少登记/)
  const payload=JSON.stringify({schema_version:'analysis-input-v1',anchor:{observation_id:obs,record_version:1},purpose:'single_frame',quality:{creation_invoked_inference:false,continuous_video_verified:false}})
  const digest=Buffer.from(await webcrypto.subtle.digest('SHA-256',new TextEncoder().encode(payload))).toString('hex')
  globalThis.fetch=async()=>new Response(payload,{headers:{'X-Content-SHA256':digest}})
  const input={input_id:task,anchor_observation_id:obs,anchor_record_version:1,purpose:'single_frame',manifest_sha256:digest}
  assert.equal((await downloadInput(input)).type,'application/json')
  await assert.rejects(downloadInput({...input,manifest_sha256:'a'.repeat(64)}),/摘要不一致/)
})
