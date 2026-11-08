import test from 'node:test'
import assert from 'node:assert/strict'
import {chineseMessage,chineseValidationMessage,installChineseValidation} from '../src/chinese-copy.js'
import {uncertainLabels} from '../src/result-format.js'

test('浏览器异常和缺失消息统一为中文，保留已知中文业务说明',()=>{
  assert.equal(chineseMessage(new TypeError('Failed to fetch')),'网络连接失败，请检查连接后重试。')
  assert.equal(chineseMessage('NetworkError when attempting to fetch resource.'),'网络连接失败，请检查连接后重试。')
  assert.equal(chineseMessage(new Error('Unexpected token < in JSON'),'读取记录失败。'),'读取记录失败。')
  assert.equal(chineseMessage('The operation timed out.'),'请求超时，请稍后重试。')
  assert.equal(chineseMessage('The operation was aborted.'),'操作已取消，可重新尝试。')
  assert.equal(chineseMessage('线索版本已变化，请刷新。'),'线索版本已变化，请刷新。')
  assert.equal(chineseMessage('错误：Internal server exception','服务暂不可用。'),'服务暂不可用。')
  assert.equal(chineseMessage(undefined,''),'')
  assert.deepEqual(uncertainLabels(['vehicle_color','new_internal_field','another_internal_field']),['车身颜色','其他特征'])
})

test('必填、格式、数值与长度校验使用中文',()=>{
  for(const [flag,expected] of [['valueMissing','请填写此项。'],['typeMismatch','请输入有效的邮箱地址。'],['badInput','请输入有效的数值。'],['patternMismatch','请按要求的格式填写。'],['tooShort','请至少输入 3 个字符。'],['tooLong','请最多输入 8 个字符。'],['rangeUnderflow','请输入不小于 1 的数值。'],['rangeOverflow','请输入不大于 5 的数值。'],['stepMismatch','输入的数值不符合允许的间隔。']]){
    assert.equal(chineseValidationMessage({validity:{[flag]:true},type:'email',minLength:3,maxLength:8,min:'1',max:'5'}),expected)
  }
})

test('原生校验及时清除自有错误，同时保留业务校验与卸载边界',()=>{
  const listeners=new Map(),document={documentElement:{lang:'en'},addEventListener:(name,fn)=>listeners.set(name,fn),removeEventListener:(name,fn)=>{assert.equal(listeners.get(name),fn);listeners.delete(name)}}
  const dispose=installChineseValidation(document)
  assert.equal(document.documentElement.lang,'zh-CN')
  const control={validity:{valueMissing:true},setCustomValidity(value){this.message=value;this.validationMessage=value;this.validity.customError=Boolean(value)}}
  listeners.get('invalid')({target:control});assert.equal(control.message,'请填写此项。')
  listeners.get('input')({target:control});assert.equal(control.message,'')
  control.setCustomValidity('业务约束不满足。');listeners.get('invalid')({target:control});assert.equal(control.message,'业务约束不满足。')
  control.setCustomValidity('');listeners.get('invalid')({target:control})
  control.setCustomValidity('新的业务约束。');listeners.get('input')({target:control});assert.equal(control.message,'新的业务约束。')
  dispose();assert.equal(listeners.size,0)
})
