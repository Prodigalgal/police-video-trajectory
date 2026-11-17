import test from 'node:test'
import assert from 'node:assert/strict'
import {bindingCommand,draftBindings,localCatalogs} from '../src/local-models.js'
const processing={id:'processing',available:true,source_current:true,revision:0,base_sha256:'a'.repeat(64),bindings:{license_plate:'plate',text_ocr:null},profiles:[{profile:'plate',sha256:'b'.repeat(64),capabilities:['license_plate']}]}
const appearance={id:'appearance',available:true,source_current:true,revision:0,base_sha256:'c'.repeat(64),bindings:{appearance:'visual'},profiles:[{profile:'visual',sha256:'d'.repeat(64),dimensions:384,installed:true}]}
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
test('登记但缺少已校验权重的配置可以显示，不能保存为活动模型',()=>{
  const missing={...appearance,profiles:[{...appearance.profiles[0],installed:false}]}
  assert.equal(localCatalogs({schema_version:'local-model-bindings-v1',catalogs:[processing,missing]})[1].profiles[0].installed,false)
  assert.throws(()=>bindingCommand(missing,missing.bindings,'request'),/权重校验失败/)
})

test('人车模型原子热切换兼容旧目录，拒绝串用专用模型与未安装权重',()=>{
  const person={profile:'person-reid',sha256:'e'.repeat(64),dimensions:512,installed:true,target_types:['person']}
  const vehicle={profile:'vehicle-reid',sha256:'f'.repeat(64),dimensions:768,installed:true,target_types:['vehicle']}
  const expanded={...appearance,profiles:[...appearance.profiles,person,vehicle]}
  assert.deepEqual(draftBindings(appearance),{appearance:'visual',person:'visual',vehicle:'visual'})
  const bindings={appearance:'visual',person:'person-reid',vehicle:'vehicle-reid'}
  assert.deepEqual(bindingCommand(expanded,bindings,'request').bindings,bindings)
  assert.equal(localCatalogs({schema_version:'local-model-bindings-v1',catalogs:[processing,{...expanded,bindings}]})[1].bindings.vehicle,'vehicle-reid')
  assert.throws(()=>bindingCommand(expanded,{...bindings,vehicle:'person-reid'},'request'),/支持该用途/)
  assert.throws(()=>bindingCommand({...expanded,profiles:[...appearance.profiles,person,{...vehicle,installed:false}]},bindings,'request'),/未安装/)
  assert.throws(()=>localCatalogs({schema_version:'local-model-bindings-v1',catalogs:[processing,{...expanded,profiles:[{...person,dimensions:2048}]}]}))
})
