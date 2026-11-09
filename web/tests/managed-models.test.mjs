import test from 'node:test'
import assert from 'node:assert/strict'
import {selectionKey,selectionValue,latestProfiles,appliedConsumers,commandRequest} from '../src/managed-models.js'

test('绑定保留明确修订，停用不猜测其他模型',()=>{
  const profile={profile_id:'a',version:3}
  assert.deepEqual(selectionValue(selectionKey(profile)),profile)
  assert.equal(selectionValue(''),null)
  assert.deepEqual(latestProfiles([{profile_id:'a',version:2},{profile_id:'b',version:1},profile]),[profile,{profile_id:'b',version:1}])
})
test('应用状态排除旧版本和过期心跳',()=>{
  const now=Date.now(),current={consumer_id:'gateway',revision:2,seen_at:new Date(now).toISOString()}
  assert.deepEqual(appliedConsumers({state:{revision:2},consumers:[current,{...current,revision:1},{...current,seen_at:new Date(now-31000).toISOString()}]},now),[current])
})
test('相同失败请求可重试，变更内容生成新编号',()=>{
  const reference={current:null},first=commandRequest(reference,{expected_revision:1})
  assert.deepEqual(commandRequest(reference,{expected_revision:1}),first)
  assert.notEqual(commandRequest(reference,{expected_revision:2}).request_id,first.request_id)
})
