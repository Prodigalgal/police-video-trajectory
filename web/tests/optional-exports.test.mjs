import test from 'node:test'
import assert from 'node:assert/strict'
import {exportFeature,exportFeatureCommand,recordingFeature} from '../src/storage-feature-client.js'
import {validateExportModule,validateExportInfo,exportStateLabel} from '../src/export-client.js'
const feature={id:'evidence_export',installed:true,enabled:false,version:1,plugin:'bound_video_package_v1',parameters:{},default_enabled:false,apply_scope:'new_exports',plugins:[{id:'bound_video_package_v1',label:'已绑定录像打包'}]}
test('导出开关需要已安装模块，关闭不依赖安装，拒绝跨模块参数',()=>{
  const snapshot={schema_version:'pipeline-features-v1',features:[feature]}
  assert.equal(exportFeature(snapshot).enabled,false)
  assert.deepEqual(exportFeatureCommand(feature,{...feature,enabled:true}),{expected_version:1,enabled:true,plugin:'bound_video_package_v1',parameters:{}})
  assert.throws(()=>exportFeatureCommand({...feature,installed:false},{...feature,enabled:true}))
  assert.equal(exportFeatureCommand({...feature,installed:false},feature).enabled,false)
  assert.throws(()=>exportFeatureCommand(feature,{...feature,parameters:{seconds:5}}))
  assert.throws(()=>recordingFeature(snapshot))
})
test('安装和在线状态不能替代实际开关，矛盾的可制作标志被拒绝',()=>{
  const status={schema_version:'export-module-v1',installed:true,enabled:false,version:1,plugin:'bound_video_package_v1',online:true,consumer_count:1,consumer_versions:['0.35.0'],can_create:false}
  assert.equal(validateExportModule(status).can_create,false)
  assert.throws(()=>validateExportModule({...status,can_create:true}))
  assert.throws(()=>validateExportModule({...status,consumer_count:0}))
  assert.equal(validateExportModule({...status,enabled:true,can_create:true}).can_create,true)
  assert.equal(exportStateLabel({state:'cancelled',last_error_code:'export_expired'}),'已过期')
})
test('新卷两帧支持100卷，旧四帧分卷继续接受',()=>{
  const collection={collection_id:'collection',revision:1}
  assert.equal(validateExportInfo({collection_id:'collection',collection_revision:1,parts:100,frame_count:200,frames_per_part:2},collection).parts,100)
  assert.equal(validateExportInfo({collection_id:'collection',collection_revision:1,parts:50,frame_count:200,frames_per_part:4},collection).parts,50)
  assert.throws(()=>validateExportInfo({collection_id:'collection',collection_revision:1,parts:50,frame_count:200,frames_per_part:2},collection))
})
