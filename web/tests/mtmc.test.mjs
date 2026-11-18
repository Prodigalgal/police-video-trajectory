// Nonvisual response/permission contracts, not an algorithm accuracy dataset.
import test from 'node:test'
import assert from 'node:assert/strict'
import {mtmcCommand,mtmcPeriod,mtmcSourcePosition,validateMtmc,validateMtmcCapabilities} from '../src/mtmc-client.js'
const anchor={observation_id:'12345678-1234-1234-1234-123456789001',frame_id:'12345678-1234-1234-1234-123456789002',record_version:1,camera_id:'recording-01',target_type:'vehicle'}
const other={...anchor,observation_id:'12345678-1234-1234-1234-123456789003',camera_id:'recording-02'}
const id='12345678-1234-1234-1234-123456789004',hash='a'.repeat(64)
function response(){return {schema_version:'mtmc-results-v1',query_id:id,anchor:{...anchor},summary:{},runs:[{id:'visual:evidence-fusion-v1',profile:'visual',profile_sha256:hash,strategy:'evidence-fusion-v1',state:'completed',elapsed_ms:1,total_candidate_periods:1}],selected_run:'visual:evidence-fusion-v1',offset:0,has_more:false,next_offset:null,expires_at:'2026-11-17T20:00:00Z',items:[{representative:{...other},identity_verified:false,appearance_similarity:.8,rank_score:.7,reasons:['外观相似候选'],sightings:[{observation:{...other}}],period:{clock:'relative_media',start_ms:1000,end_ms:2000}}]}}
test('跨镜关联绑定目标修订、模型空间和分页方案，不把身份确认混入候选',()=>{
  assert.equal(validateMtmc(response(),anchor).items.length,1)
  for(const mutate of [value=>{value.anchor.record_version=2},value=>{value.items[0].identity_verified=true},value=>{value.items[0].representative.camera_id=anchor.camera_id},value=>{value.selected_run='other:evidence-fusion-v1'},value=>{value.runs[0].profile_sha256='broken'},value=>{value.has_more=true;value.next_offset=0},value=>{value.items[0].appearance_similarity=NaN},value=>{value.items[0].period.clock='verified_source'}]){const value=response();mutate(value);assert.throws(()=>validateMtmc(value,anchor),/不一致/)}
  const changed=response();changed.runs[0].profile_sha256='b'.repeat(64)
  assert.throws(()=>validateMtmc(changed,{...anchor,frozenRuns:response().runs}),/不一致/)
})
test('缺特征方案不能伪造候选，未知时钟不推算拍摄时间',()=>{
  const value=response();value.runs[0].state='feature_missing'
  assert.throws(()=>validateMtmc(value,anchor),/不一致/)
  value.items=[];assert.equal(validateMtmc(value,anchor).items.length,0)
  assert.equal(mtmcPeriod({clock:'unknown',start_ms:1234}),'来源位置待核实')
  assert.match(mtmcPeriod({clock:'relative_media',start_ms:1000,end_ms:2000}),/会话位置 00:00:01/)
  assert.match(mtmcSourcePosition({period:{clock:'unknown'},sightings:[{clock:{media_offset_ms:5000}},{clock:{media_offset_ms:8000}}]}),/源录像偏移 00:00:05 — 00:00:08/)
  assert.match(mtmcSourcePosition({period:{clock:'unknown'},source_locator:{platform_id:'wvp-nj',channel_id:'3402001'},sightings:[{clock:{media_offset_ms:5000}}]}),/上级通道 wvp-nj\/3402001/)
  assert.equal(mtmcSourcePosition({period:{clock:'unknown'},sightings:[{clock:{media_offset_ms:5000}},{clock:{media_offset_ms:null}}]}),'来源位置待核实')
})
test('查询只提交有界模型选择，不接收任意端点或重复模型',()=>{
  const draft={strategy:'evidence-fusion-v1',profiles:['visual'],cameras:['recording-02'],seconds:600}
  const command=mtmcCommand(anchor,draft,id)
  assert.deepEqual(command.profiles,['visual']);assert.equal(command.expected_version,1);assert.equal(Object.keys(command).includes('endpoint'),false)
  for(const patch of [{profiles:[]},{profiles:['visual','visual']},{profiles:['../model']},{seconds:3601},{cameras:['http://camera.invalid']}])assert.throws(()=>mtmcCommand(anchor,{...draft,...patch},id))
  assert.throws(()=>validateMtmcCapabilities({enabled:true,profiles:[{id:'visual',sha256:hash,target_types:['vehicle','vehicle']}],strategies:[{id:'appearance-v1',label:'外观关联'}],cameras:[]}))
})

test('扩展策略由服务端登记目录决定，旧前端不锁死两种算法',()=>{
  const plugins=['appearance-v1','evidence-fusion-v1','camera-aware-v2']
  const capabilities={enabled:true,profiles:[{id:'visual',sha256:hash,target_types:['vehicle']}],strategies:[{id:'camera-aware-v2',label:'点位关系关联'}],cameras:[]}
  assert.equal(validateMtmcCapabilities(capabilities).strategies[0].id,'camera-aware-v2')
  const draft={strategy:'camera-aware-v2',profiles:['visual'],cameras:[],seconds:600}
  assert.equal(mtmcCommand(anchor,draft,id,plugins).strategy,'camera-aware-v2')
  assert.throws(()=>mtmcCommand(anchor,draft,id,['appearance-v1']))
  const value=response();value.runs[0].strategy='camera-aware-v2';value.runs[0].id='visual:camera-aware-v2';value.selected_run=value.runs[0].id
  assert.equal(validateMtmc(value,{...anchor,allowedStrategies:plugins}).selected_run,'visual:camera-aware-v2')
  assert.throws(()=>validateMtmc(value,{...anchor,allowedStrategies:['appearance-v1']}))
})
