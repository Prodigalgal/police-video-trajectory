import test from 'node:test'
import assert from 'node:assert/strict'
import {validateMediaJob,validateMediaPage,validateMediaManifest,checkedMediaBytes,mediaJson} from '../src/media-job-client.js'
import {createHash} from 'node:crypto'

function job(){return {job_id:'11111111-1111-4111-8111-111111111111',source_id:'22222222-2222-4222-8222-222222222222',camera_id:'recording-01',input_kind:'session',input_sha256:'a'.repeat(64),revision:2,attempts:0,start_ms:0,end_ms:5000,state:'completed',dispatch_state:'completed',claims_enabled:false,clip_id:'11111111-1111-4111-8111-111111111111',manifest_sha256:'b'.repeat(64)}}
test('media jobs reject wrong scope, false completion, bad phase and cursor cycles',()=>{
  const value=job();assert.equal(validateMediaJob(value,'recording-01'),value)
  for(const changed of [{camera_id:'recording-02'},{clip_id:null},{dispatch_state:'paused'},{revision:0},{start_ms:true}])assert.throws(()=>validateMediaJob({...value,...changed},'recording-01'))
  assert.throws(()=>validateMediaPage({items:[value,value],has_more:false,next_cursor:null},'recording-01'))
  assert.throws(()=>validateMediaPage({items:[value],has_more:true,next_cursor:value.source_id},'recording-01'))
  assert.equal(validateMediaPage({items:[value],has_more:true,next_cursor:value.job_id},'recording-01').items.length,1)
  assert.throws(()=>validateMediaPage({items:[value],has_more:true,next_cursor:value.job_id},'recording-01',value.job_id))
})
test('real response bytes are checked as ArrayBuffer and corrupted or oversized bodies stop',async()=>{
  const original=globalThis.fetch,raw=new TextEncoder().encode('media protocol receipt'),digest=createHash('sha256').update(raw).digest('hex')
  try{
    globalThis.fetch=async()=>new Response(raw);assert.deepEqual(await checkedMediaBytes('media-clips/example/manifest',digest,100),raw)
    await assert.rejects(checkedMediaBytes('media-clips/example/manifest','0'.repeat(64),100),/摘要不一致/)
    await assert.rejects(checkedMediaBytes('media-clips/example/manifest',digest,3),/不一致/)
  }finally{globalThis.fetch=original}
})
test('malformed login failures remain unauthorized and network errors have a recovery message',async()=>{
  const original=globalThis.fetch
  try{
    globalThis.fetch=async()=>new Response('null',{status:401});await assert.rejects(mediaJson('features'),error=>error.unauthorized===true&&/登录/.test(error.message))
    globalThis.fetch=async()=>{throw new TypeError('Failed to fetch')};await assert.rejects(mediaJson('features'),/检查连接后重试/)
  }finally{globalThis.fetch=original}
})
test('media manifest remains bound to job, source, clock, and genuine sample offsets',()=>{
  const current=job(),value={clip_id:current.job_id,source_id:current.source_id,camera_id:current.camera_id,requested_start_ms:0,requested_end_ms:5000,schema_version:'recorded-session-clip-v1',quality:{inference_invoked:false},actual_start_us:0,actual_end_us:4966667,session_input:{source_id:current.source_id,camera_id:current.camera_id,version:'session-segment-input-v1'},video:{sha256:'c'.repeat(64)},samples:[0,1].map(index=>({index,bytes:10,sha256:'d'.repeat(64),offset_ms:index*1000,source_pts_us:index*1000000}))}
  assert.equal(validateMediaManifest(value,current),value)
  for(const changed of [{source_id:current.job_id},{schema_version:'recorded-media-clip-v1'},{quality:{inference_invoked:true}},{samples:[{...value.samples[0],source_pts_us:1000000},value.samples[1]]}])assert.throws(()=>validateMediaManifest({...value,...changed},current))
})
