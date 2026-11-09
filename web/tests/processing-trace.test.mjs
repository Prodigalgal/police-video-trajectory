import test from 'node:test'
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import {verifiedTrace,traceError,traceStateLabels} from '../src/processing-trace.js'
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
