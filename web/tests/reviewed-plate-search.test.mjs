import test from 'node:test'
import assert from 'node:assert/strict'
import {validateSearchResult,validateGuidance,removeSearchCondition} from '../src/search-guidance.js'

function guidance(){return {schema_version:'search-guidance-v1',context:'signed.context',expires_at:'2026-11-09T12:00:00Z',reason:'ready',camera_choices:[],conditions:[{id:'target',label:'目标 · 车辆',removable:false},{id:'plate',label:'已复核车牌 · 闽E12345',removable:true}]}}
function result(){return {status:'ok',query:'闽E12345',plan:{targetType:'vehicle',start:null,end:null,locationText:null,cameraIds:[],attributes:null,textTerms:[],clarifications:[],plateNumber:'闽E12345'},results:[],total:0,guidance:guidance()}}

test('已复核车牌结果与条件可以进入同一搜索页并显式移除',()=>{
  const value=validateSearchResult(result(),'闽E12345')
  assert.equal(value.plan.plateNumber,'闽E12345')
  assert.deepEqual(removeSearchCondition(value.guidance,'plate'),{context:'signed.context',action:'remove',condition:'plate'})
  assert.throws(()=>removeSearchCondition(value.guidance,'target'))
})
test('旧语义结果仍兼容，非法车牌类型和未知条件继续拒绝',()=>{
  const value=result();delete value.plan.plateNumber;value.guidance.conditions.pop()
  assert.doesNotThrow(()=>validateSearchResult(value,'闽E12345'))
  value.plan.plateNumber=123;assert.throws(()=>validateSearchResult(value,'闽E12345'))
  const invalid=guidance();invalid.conditions.push({id:'raw_dsl',label:'无效',removable:true});assert.throws(()=>validateGuidance(invalid))
})
test('新增车牌条件后完整条件容量为四十三项，重复项仍拒绝',()=>{
  const value=guidance(),ids=['target','time','location','cameras','plate','upper_color','lower_color','clothing_color','vehicle_color','transport_type','visible_action',...Array.from({length:12},(_,i)=>`carrying:${i}`),...Array.from({length:20},(_,i)=>`text:${i}`)]
  value.conditions=ids.map(id=>({id,label:'条件',removable:true}))
  assert.equal(value.conditions.length,43);assert.doesNotThrow(()=>validateGuidance(value))
  value.conditions.push(value.conditions[0]);assert.throws(()=>validateGuidance(value))
})
