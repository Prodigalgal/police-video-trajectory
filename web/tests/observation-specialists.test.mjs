import test from 'node:test'
import assert from 'node:assert/strict'
import {specialistCursor,validateObservationSpecialists} from '../src/observation-specialists.js'

const identity=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0')
const detail={observation_id:identity(1),frame_id:identity(2),camera_id:'recording-01',record_version:3}
function entry(n,created_at='2026-11-08T08:00:00Z',revision=3){
  const input={input_id:identity(n+20),anchor_observation_id:detail.observation_id,anchor_frame_id:detail.frame_id,camera_id:detail.camera_id,anchor_record_version:revision,purpose:'single_frame',manifest_sha256:'a'.repeat(64),source_status:revision===3?'not_revalidated':'stale',creation_invoked_inference:false}
  const job={job_id:identity(n),input_id:input.input_id,frame_id:detail.frame_id,camera_id:detail.camera_id,input_manifest_sha256:input.manifest_sha256,input_sha256:'b'.repeat(64),operation:'license_plate',profile:'saved-native-profile',state:'completed',dispatch_state:'completed',revision:2,attempts:0,result_sha256:'c'.repeat(64),created_at}
  return {input,job}
}
function page(items,more=false){return {schema_version:'observation-specialists-v1',anchor:{...detail},items,has_more:more,next_cursor:more?items.at(-1)?.job.job_id:null}}

test('本线索原帧任务采用独立来源校验，保留旧输入状态且不宣称身份',()=>{
  const value=page([entry(4),entry(3,'2026-11-08T07:00:00Z',2)])
  assert.equal(validateObservationSpecialists(value,detail),value)
  for(const change of [{schema_version:'other'},{anchor:{...detail,record_version:2}},{anchor:{...detail,frame_id:identity(99)}}])assert.throws(()=>validateObservationSpecialists({...value,...change},detail))
  for(const patch of [{anchor_observation_id:identity(99)},{anchor_frame_id:identity(99)},{camera_id:'recording-02'},{source_status:'stale'},{anchor_record_version:4}]){
    const current=entry(4);current.input={...current.input,...patch};assert.throws(()=>validateObservationSpecialists(page([current]),detail))
  }
  const wrong=entry(4);wrong.job.frame_id=identity(99);assert.throws(()=>validateObservationSpecialists(page([wrong]),detail))
})

test('时间倒序保留纳秒精度，相同时刻按编号倒序且拒绝重复与反向翻页',()=>{
  const newer=entry(3,'2026-11-08T08:00:00.000002Z'),older=entry(4,'2026-11-08T08:00:00.000001Z')
  assert.equal(validateObservationSpecialists(page([newer,older]),detail).items.length,2)
  assert.throws(()=>validateObservationSpecialists(page([older,newer]),detail))
  assert.equal(validateObservationSpecialists(page([entry(4),entry(3)]),detail).items.length,2)
  assert.throws(()=>validateObservationSpecialists(page([entry(3),entry(4)]),detail))
  assert.throws(()=>validateObservationSpecialists(page([entry(4),entry(4)]),detail))
  assert.equal(validateObservationSpecialists(page([older]),detail,specialistCursor(newer)).items.length,1)
  assert.throws(()=>validateObservationSpecialists(page([newer]),detail,specialistCursor(older)))
})

test('空页、容量、末页及游标必须一致，非法日期不能形成排序依据',()=>{
  assert.equal(validateObservationSpecialists(page([]),detail).has_more,false)
  const value=page([entry(4),entry(3)],true)
  assert.equal(validateObservationSpecialists(value,detail,null,2).next_cursor,identity(3))
  for(const patch of [{next_cursor:identity(99)},{items:[]},{has_more:false}])assert.throws(()=>validateObservationSpecialists({...value,...patch},detail,null,2))
  assert.throws(()=>validateObservationSpecialists(value,detail,null,1))
  for(const date of ['invalid','2026-02-30T00:00:00Z','2026-11-08T08:00:00+08:00'])assert.throws(()=>validateObservationSpecialists(page([entry(4,date)]),detail))
})
