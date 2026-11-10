import test from 'node:test'
import assert from 'node:assert/strict'
import {imagePackageContext,imagePackagePreviewPath,imagePackageDownloadPath,validateImagePackagePreview,packageReviewLabel} from '../src/image-package-client.js'
const id='11111111-2222-4333-8444-555555555555',frame='22222222-3333-4444-8555-666666666666',observation='33333333-4444-4555-8666-777777777777'
const context=imagePackageContext({collection_id:id,revision:1},1)
function preview(){return {schema_version:'evidence-package-preview-v1',collection_id:id,collection_revision:1,part:1,parts:1,frame_count:1,frames_per_part:16,
  source_sha256:'a'.repeat(64),prepared_at:'2026-11-10T09:00:00Z',image_bytes:100,download_allowed:true,download_reason:null,
  frames:[{frame_id:frame,camera_id:'camera-1',camera_name:'点位一',sha256:'b'.repeat(64),bytes:100,capture_integrity:'matched',source_time:null,received_at:'2026-11-10T09:00:00Z',source_time_origin:'unknown',observations:[{observation_id:observation,record_version:1,target_type:'vehicle',review_state:'retained',review_stale:false}]}]}}
test('预览与下载绑定同一清单版本、分卷与来源摘要',()=>{
  const value=validateImagePackagePreview(preview(),context),url=new URL(imagePackageDownloadPath(value,context),'https://application.invalid')
  assert.equal(url.searchParams.get('expected_source_sha256'),value.source_sha256);assert.equal(url.searchParams.get('part'),'1')
  assert.ok(imagePackagePreviewPath(context).endsWith('package-preview?revision=1&part=1'))
  assert.throws(()=>imagePackageDownloadPath(value,imagePackageContext({collection_id:id,revision:2},1)))
  assert.throws(()=>validateImagePackagePreview(value,imagePackageContext({collection_id:id,revision:1},2)))
})
test('重复原图/目标、错误容量或核验状态不能放行',()=>{
  for(const change of ['duplicate_frame','duplicate_target','size','capture','bad_sha','allow']){
    const value=preview()
    if(change==='duplicate_frame')value.frames.push(structuredClone(value.frames[0]))
    if(change==='duplicate_target')value.frames[0].observations.push(structuredClone(value.frames[0].observations[0]))
    if(change==='size')value.image_bytes++
    if(change==='capture')value.frames[0].capture_integrity='mismatch'
    if(change==='bad_sha')value.source_sha256='broken'
    if(change==='allow')value.download_allowed=false
    assert.throws(()=>validateImagePackagePreview(value,context))
  }
})
test('空清单可核验但不提供新界面下载，过期复核单独标示',()=>{
  const value={...preview(),frame_count:0,frames:[],image_bytes:0,download_allowed:false,download_reason:'empty_package'}
  assert.doesNotThrow(()=>validateImagePackagePreview(value,context));assert.throws(()=>imagePackageDownloadPath(value,context))
  assert.equal(packageReviewLabel({review_state:'retained',review_stale:true}),'原复核已过期')
  assert.equal(packageReviewLabel({review_state:'excluded',review_stale:false}),'已排除')
})
test('最后分卷只接受余下原图，重新选卷后旧预览不能生成下载',()=>{
  const value={...preview(),part:2,parts:2,frame_count:17},second=imagePackageContext({collection_id:id,revision:1},2)
  assert.doesNotThrow(()=>validateImagePackagePreview(value,second))
  assert.throws(()=>imagePackageDownloadPath(value,context))
  value.frames.push(structuredClone(value.frames[0]));assert.throws(()=>validateImagePackagePreview(value,second))
})
