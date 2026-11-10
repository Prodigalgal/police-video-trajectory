import test from 'node:test'
import assert from 'node:assert/strict'
import {validateProviderCatalog,chooseCatalogModel,providerCatalogPath} from '../src/provider-catalog.js'
const provider={id:'approved',roles:['text','vision'],protocols:['responses','chat_completions']}
const model=()=>({id:'model-id',name:'候选模型',input_media:['text','image'],declared_roles:['text','vision'],allowed_roles:['text','vision'],protocols:['responses'],streaming:true,reasoning_levels:['none'],tools:false,analysis_reason:null,inference_verified:false})
const catalog=()=>({schema_version:'provider-model-catalog-v1',provider:'approved',configuration_sha256:'a'.repeat(64),catalog_sha256:'b'.repeat(64),cached:false,state:'ready',checked_at:'2026-11-10T12:00:00Z',models:[model()],diagnostics:null,inference_verified:false})
test('目录只表示声明，未知流式能力和媒体专用模型不能选为分析模型',()=>{
  assert.equal(validateProviderCatalog(catalog(),provider).inference_verified,false)
  for(const update of [{streaming:null},{allowed_roles:[]},{analysis_reason:'media_endpoint_required'}])assert.throws(()=>chooseCatalogModel({},Object.assign(model(),update)))
  assert.throws(()=>validateProviderCatalog({...catalog(),provider:'another'},provider))
  assert.throws(()=>validateProviderCatalog({...catalog(),models:[model(),model()]},provider))
  assert.throws(()=>validateProviderCatalog({...catalog(),models:[{...model(),allowed_roles:['face']}]},provider))
})
test('选择模型保留已有显示名与文件方式，重置不支持的协议、工具和推理强度',()=>{
  const form={display_name:'现有显示名',provider:'approved',protocol:'chat_completions',roles:['vision'],image_transport:'file',output_mode:'tool',reasoning_effort:'high',json_mode:true}
  const chosen=chooseCatalogModel(form,model())
  assert.equal(chosen.display_name,'现有显示名');assert.equal(chosen.protocol,'responses');assert.equal(chosen.output_mode,'text');assert.equal(chosen.json_mode,false);assert.equal(chosen.reasoning_effort,'');assert.equal(chosen.image_transport,'file')
  assert.deepEqual(form.roles,['vision']);assert.deepEqual(chosen.roles,['text','vision'])
  assert.equal(chooseCatalogModel(form,{...model(),allowed_roles:['text']}).image_transport,'inline')
})
test('诊断只允许有界字段并保持失败与空目录的区别',()=>{
  const value={...catalog(),state:'unavailable',models:[],diagnostics:{category:'credential_rejected',upstream_status:401,upstream_request_id:'11111111-2222-4333-8444-555555555555'}}
  assert.equal(validateProviderCatalog(value,provider).state,'unavailable')
  for(const update of [{message:'private'},{upstream_status:200.5},{upstream_error_code:'private token content'},{category:'unknown'}])assert.throws(()=>validateProviderCatalog({...value,diagnostics:{...value.diagnostics,...update}},provider))
  assert.equal(validateProviderCatalog({...catalog(),models:[]},provider).state,'ready')
})
test('元数据入口只能访问提供商标识，刷新使用独立路径',()=>{
  assert.equal(providerCatalogPath('approved'),'models/providers/approved/models')
  assert.equal(providerCatalogPath('approved',true),'models/providers/approved/models/refresh')
  for(const value of ['../private','https://private.invalid','a?key=private',''])assert.throws(()=>providerCatalogPath(value))
})
