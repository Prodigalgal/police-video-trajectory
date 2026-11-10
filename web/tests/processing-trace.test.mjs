import test from 'node:test'
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import {verifiedTrace,traceError,traceStateLabels,traceRequestPath,traceResourceSummary} from '../src/processing-trace.js'
function fixture(){
  const expected={frame:'00000000-0000-4000-8000-000000000001',observation:'00000000-0000-4000-8000-000000000002',version:2}
  const manifest=JSON.stringify({schema_version:'processing-replay-v1',frame_id:expected.frame,camera_id:'camera-1',image_sha256:'a'.repeat(64),image_available:true,request_available:false})
  return [expected,{schema_version:'processing-trace-v1',frame_id:expected.frame,observation_id:expected.observation,record_version:2,camera_id:'camera-1',checks:[],stages:[],replay_manifest_json:manifest,replay_manifest_sha256:createHash('sha256').update(manifest).digest('hex')}]
}
test('处理还原记录在导出前核验摘要、观测版本与原始帧',async()=>{
  const [expected,value]=fixture()
  assert.equal((await verifiedTrace(value,expected)).manifest.request_available,false)
  for(const patch of [{record_version:3},{frame_id:'other'},{replay_manifest_sha256:'b'.repeat(64)},{replay_manifest_json:value.replay_manifest_json+' '}])await assert.rejects(()=>verifiedTrace({...value,...patch},expected))
})
test('通过摘要也不能把其他点位的还原记录当成本记录',async()=>{
  const [expected,value]=fixture();const manifest=JSON.parse(value.replay_manifest_json);manifest.camera_id='camera-2'
  value.replay_manifest_json=JSON.stringify(manifest);value.replay_manifest_sha256=createHash('sha256').update(value.replay_manifest_json).digest('hex')
  await assert.rejects(()=>verifiedTrace(value,expected));assert.match(traceError(409),/更新/);assert.equal(traceStateLabels.not_checked,'本次未核验')
})
test('普通读取不隐式核验资源，主动请求保留观测版本和管理员路径边界',()=>{
  const [target]=fixture()
  assert.equal(new URL(traceRequestPath(target),'https://local.invalid').searchParams.has('verify_assets'),false)
  const verified=new URL(traceRequestPath(target,true),'https://local.invalid')
  assert.equal(verified.searchParams.get('observation_version'),'2');assert.equal(verified.searchParams.get('verify_assets'),'true')
  assert.match(traceRequestPath({frame:target.frame},true),/^\/api\/admin\/processing-traces\/frames\/.+\?verify_assets=true$/)
  assert.throws(()=>traceRequestPath({...target,version:0},true))
})
test('资源核验解释使用中文并区分缺失、变化、故障和超限',()=>{
  assert.match(traceResourceSummary({name:'elasticsearch_document',details:{expected:2,matched:1,unavailable:1}}),/暂不可读 1 条/)
  assert.match(traceResourceSummary({name:'elasticsearch_document',details:{reason:'observation_limit_exceeded'}}),/未做完整对照/)
  assert.match(traceResourceSummary({name:'recording_file',state:'changed',details:{reason:'recording_manifest_changed'}}),/已变化/)
  assert.equal(traceResourceSummary({name:'recording_file',state:'not_checked'}),'')
})
