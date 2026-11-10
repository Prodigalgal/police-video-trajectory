import test from 'node:test'
import assert from 'node:assert/strict'
import {recordingCommand,recordingFeature} from '../src/storage-feature-client.js'

const feature={id:'frame_recording',enabled:false,plugin:'short_segment_v1',parameters:{},version:1,default_enabled:false,plugins:[{id:'short_segment_v1',label:'同会话短段'},{id:'context_window_v1',label:'前后上下文'}]}
test('选择前后范围不能自行开启录制，关闭命令保留明确版本',()=>{
  assert.equal(recordingFeature({schema_version:'pipeline-features-v1',features:[feature]}),feature)
  const command=recordingCommand(feature,{enabled:false,plugin:'context_window_v1',parameters:{seconds:10}},'request')
  assert.equal(command.enabled,false);assert.equal(command.expected_version,1);assert.deepEqual(command.parameters,{seconds:10})
})
test('无效配置和非安装插件不能提交',()=>{
  assert.throws(()=>recordingFeature({schema_version:'pipeline-features-v1',features:[feature,feature]}))
  assert.throws(()=>recordingCommand(feature,{...feature,plugin:'unknown'},'request'))
  assert.throws(()=>recordingCommand(feature,{...feature,plugin:'context_window_v1',parameters:{seconds:60}},'request'))
  assert.throws(()=>recordingFeature({schema_version:'pipeline-features-v1',features:[{...feature,enabled:'true'}]}))
})
