import test from 'node:test'
import assert from 'node:assert/strict'
import {bindingCommand,localCatalogs} from '../src/local-models.js'
const processing={id:'processing',available:true,source_current:true,revision:0,base_sha256:'a'.repeat(64),bindings:{license_plate:'plate',text_ocr:null},profiles:[{profile:'plate',sha256:'b'.repeat(64),capabilities:['license_plate']}]}
const appearance={id:'appearance',available:true,source_current:true,revision:0,base_sha256:'c'.repeat(64),bindings:{appearance:'visual'},profiles:[{profile:'visual',sha256:'d'.repeat(64),dimensions:384}]}
test('停用只提交用途选择与版本，不提交端点或凭据',()=>{
  assert.equal(localCatalogs({schema_version:'local-model-bindings-v1',catalogs:[processing,appearance]}).length,2)
  const command=bindingCommand(processing,{license_plate:null,text_ocr:null},'request')
  assert.deepEqual(command.bindings,{license_plate:null,text_ocr:null});assert.equal(command.expected_revision,0)
})
test('未登记配置、错用能力及混合用途不能提交',()=>{
  assert.throws(()=>bindingCommand(processing,{license_plate:'missing',text_ocr:null},'request'))
  assert.throws(()=>bindingCommand(processing,{license_plate:null,text_ocr:'plate'},'request'))
  assert.throws(()=>bindingCommand(appearance,{appearance:null},'request'))
  assert.throws(()=>localCatalogs({schema_version:'local-model-bindings-v1',catalogs:[processing,processing]}))
})
test('基线改变仍能展示原选择并要求重新保存，不能伪装有效绑定',()=>{
  const changed={...processing,source_current:false,bindings:{license_plate:'removed',text_ocr:null}}
  assert.equal(localCatalogs({schema_version:'local-model-bindings-v1',catalogs:[changed,appearance]})[0].source_current,false)
  assert.throws(()=>bindingCommand(changed,changed.bindings,'request'))
})
