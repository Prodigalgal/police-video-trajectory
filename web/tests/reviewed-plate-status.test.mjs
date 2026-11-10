import test from 'node:test'
import assert from 'node:assert/strict'
import {plateSearchContext,plateSearchPath,validatePlateSearchStatus,reviewedPlateSearchHref} from '../src/reviewed-plate-status.js'

const observation='11111111-2222-4333-8444-555555555555',frame='22222222-3333-4444-8555-666666666666'
const proof={job_id:'33333333-4444-4555-8666-777777777777',review_revision:2,result_sha256:'a'.repeat(64),input_manifest_sha256:'b'.repeat(64),region_index:0,review_scope:'single_frame'}
function detail(number='闽E12345'){return {observation_id:observation,record_version:1,frame_id:frame,camera_id:'camera-1',target_type:'vehicle',reviewed_plate:{state:'ready',number,proofs:[proof]}}}
function response(number='闽E12345'){return {schema_version:'reviewed-plate-search-status-v1',observation_id:observation,record_version:1,frame_id:frame,camera_id:'camera-1',state:'searchable',checked_at:'2026-11-10T08:00:00Z',number,proofs:[proof],fact_sha256:'c'.repeat(64),requested_revision:2,published_revision:2,query:'车牌 '+number}}

test('当前牌号和复核依据通过后只生成首页预填地址',()=>{
  const context=plateSearchContext(detail()),value=validatePlateSearchStatus(response(),context)
  assert.equal(plateSearchPath(context),`/api/v1/observations/${observation}/plate-search-status?observation_version=1`)
  const url=new URL(reviewedPlateSearchHref(value),'https://application.invalid')
  assert.equal(url.pathname,'/');assert.equal(url.searchParams.get('q'),'车牌 闽E12345');assert.equal(url.searchParams.size,1)
})
test('所有非就绪状态不产生可点击搜索地址',()=>{
  for(const state of ['disabled','pending','retry_wait','sync_failed','not_queued','not_indexed','stale','unavailable']){
    const value={...response(),state,query:null}
    assert.equal(reviewedPlateSearchHref(validatePlateSearchStatus(value,plateSearchContext(detail()))),null)
  }
  assert.equal(plateSearchContext({...detail(),reviewed_plate:{state:'pending'}}),null)
  assert.equal(plateSearchContext({...detail(),target_type:'person'}),null)
})
test('撤销、错来源、旧版本、换牌号及复核版本变化均使旧响应失效',()=>{
  for(const change of [{observation_id:frame},{frame_id:observation},{camera_id:'another'},{record_version:2},{number:'闽E54321'},{state:'review_required',number:null,proofs:[],query:null},{proofs:[{...proof,review_revision:3}]}]){
    assert.throws(()=>validatePlateSearchStatus({...response(),...change},plateSearchContext(detail())),error=>error.sourceChanged===true)
  }
  assert.notEqual(plateSearchContext(detail()).key,plateSearchContext({...detail(),reviewed_plate:{...detail().reviewed_plate,proofs:[{...proof,review_revision:3}]}}).key)
})
test('明确车牌格式支持新能源与警牌，拒绝模糊字符和任意搜索语句',()=>{
  for(const number of ['粤BD12345','闽E12345D','闽E1234警'])assert.ok(reviewedPlateSearchHref(validatePlateSearchStatus(response(number),plateSearchContext(detail(number)))))
  for(const number of ['闽EI2345','闽E?2345','白色车辆'])assert.throws(()=>plateSearchContext(detail(number)))
  assert.throws(()=>validatePlateSearchStatus({...response(),query:'白色车辆'},plateSearchContext(detail())))
  assert.throws(()=>validatePlateSearchStatus({...response(),published_revision:1},plateSearchContext(detail())))
})
test('依据键无关字段顺序但拒绝重复任务、缺摘要和非法同步版本',()=>{
  const reordered=Object.fromEntries(Object.entries(proof).reverse())
  assert.doesNotThrow(()=>validatePlateSearchStatus({...response(),proofs:[reordered]},plateSearchContext(detail())))
  for(const proofs of [[proof,proof],[{...proof,result_sha256:''}],[{...proof,review_scope:'identity'}]])assert.throws(()=>validatePlateSearchStatus({...response(),proofs},plateSearchContext(detail())))
  for(const change of [{state:'unknown'},{fact_sha256:''},{published_revision:-1},{requested_revision:2.1},{checked_at:'invalid'}])assert.throws(()=>validatePlateSearchStatus({...response(),...change},plateSearchContext(detail())))
})
