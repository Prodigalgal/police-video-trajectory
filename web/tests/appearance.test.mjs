import test from 'node:test'
import assert from 'node:assert/strict'
import {validBox,validateAppearance,appearanceJson,uploadAppearance} from '../src/appearance-client.js'

const id='12345678-1234-1234-1234-123456789012',profile='1'.repeat(64),image='2'.repeat(64)
const expected={id,profile,image,type:'vehicle'}
const response=()=>({schema_version:'appearance-results-v1',query_id:id,summary:{profile_sha256:profile,image_sha256:image,target_type:'vehicle'},items:[{observation_id:id,frame_id:id,target_id:id,record_version:1,camera_id:'recording-01',target_type:'vehicle',image_sha256:image,image_width:1280,image_height:720,bbox_xyxy:[0,0,100,100],similarity:.9,sightings:2,period:{clock:'relative_media',start_ms:0,end_ms:1000,association:'local_geometry_candidate'}}],offset:0,total_candidate_periods:1,has_more:false,next_offset:null})
test('目标框仅接受有界可见区域，候选必须绑定图像和向量空间',()=>{
  assert.equal(validBox([0,0,12,12],100,100),true)
  for(const box of [[-1,0,100,100],[0,0,NaN,100],[0,0,11,12]])assert.equal(validBox(box,100,100),false)
  assert.equal(validateAppearance(response(),expected).items.length,1)
  for(const mutate of [v=>{v.summary.profile_sha256='3'.repeat(64)},v=>{v.items[0].bbox_xyxy=[0,0,2000,100]},v=>{v.items[0].record_version=0},v=>{v.items[0].period.end_ms=400000},v=>{v.items[0].similarity=NaN},v=>{v.next_offset=0;v.has_more=true}]){const value=response();mutate(value);assert.throws(()=>validateAppearance(value,expected),/不一致/)}
})
test('图像检索复用有界流式解析，断流不当作成功，英文上游错误不显示',async()=>{
  const original=globalThis.fetch
  try{
    globalThis.fetch=async()=>new Response('event: status\ndata: {"message":"处理中"}\n\nevent: result\ndata: '+JSON.stringify(response())+'\n\n',{headers:{'content-type':'text/event-stream'}})
    assert.equal((await uploadAppearance(new FormData())).query_id,id)
    globalThis.fetch=async()=>new Response('event: status\ndata: {}\n\n',{headers:{'content-type':'text/event-stream'}})
    await assert.rejects(uploadAppearance(new FormData()),/中断/)
    await assert.rejects(appearanceJson({ok:false,status:410,json:async()=>({code:'appearance_query_expired',detail:'Internal database unavailable'})}),/过期/)
  }finally{globalThis.fetch=original}
})
