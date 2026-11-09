import test from 'node:test'
import assert from 'node:assert/strict'
import {validateNearbyRecords} from '../src/nearby-records.js'
const id=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0')
const detail={observation_id:id(1),frame_id:id(2),record_version:1,camera_id:'camera-1',source_type:'rtsp'}
function records(){return {schema_version:'nearby-records-v1',anchor:{observation_id:detail.observation_id,frame_id:detail.frame_id,record_version:1},time_basis:'recorded_at',sequence_eligible:false,continuous_video_verified:false,items:[{frame_id:detail.frame_id,observation_id:detail.observation_id,camera_id:'camera-1',source_type:'rtsp',preview_sha256:'a'.repeat(64),received_at:'2026-11-09T00:00:00Z',record_offset_ms:0}]}}
test('无源时钟也能浏览有原图摘要的留存帧，不能冒充多帧序列',()=>{
  assert.equal(validateNearbyRecords(records(),detail).items.length,1)
  for(const patch of [{sequence_eligible:true},{continuous_video_verified:true},{time_basis:'source_pts'}])assert.throws(()=>validateNearbyRecords({...records(),...patch},detail))
})
test('记录必须仍属于相同点位、当前观测修订且不得重复',()=>{
  const value=records()
  assert.throws(()=>validateNearbyRecords({...value,items:[{...value.items[0],camera_id:'camera-2'}]},detail))
  assert.throws(()=>validateNearbyRecords({...value,items:[...value.items,...value.items]},detail))
  assert.throws(()=>validateNearbyRecords(value,{...detail,record_version:2}))
})
