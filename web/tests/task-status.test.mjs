import test from 'node:test'
import assert from 'node:assert/strict'
import {moduleNames,taskFailure,taskPhase} from '../src/task-status.js'

test('task phases distinguish retry cooldown, lease expiry and terminal failure',()=>{
  assert.equal(taskPhase({state:'queued',scheduling_state:'retry_wait'}).label,'等待重试')
  assert.equal(taskPhase({state:'active',scheduling_state:'lease_expired'}).label,'租约过期，等待回收')
  assert.equal(taskPhase({state:'failed'}).label,'失败，需处理')
})

test('pausing new claims never relabels running or completed work as paused',()=>{
  assert.equal(taskPhase({state:'queued'},true).label,'等待领取（已暂停）')
  assert.equal(taskPhase({state:'queued',scheduling_state:'retry_wait'},true).label,'等待重试（已暂停）')
  assert.equal(taskPhase({state:'active'},true).label,'处理中')
  assert.equal(taskPhase({state:'completed'},true).label,'已完成')
})

test('legacy data falls back to base state without deriving lease status from browser clock',()=>{
  assert.equal(taskPhase({state:'active',lease_expires_at:'2000-01-01T00:00:00Z'}).phase,'active')
  assert.equal(taskPhase({state:'queued',next_attempt_at:'2100-01-01T00:00:00Z'}).phase,'queued')
  assert.equal(taskPhase({}).label,'状态未知')
})

test('model, job and commit failures have separate explanations',()=>{
  assert.equal(taskFailure('model_timeout'),'模型请求超时（360 秒）')
  assert.equal(taskFailure('job_timeout'),'单次任务超时')
  assert.equal(taskFailure('result_commit_unavailable'),'结果提交未获确认')
  assert.equal(taskFailure('new_unknown_code'),'未分类失败')
  assert.equal(taskFailure(null),'—')
})

test('module integrity failures and inconsistent states remain explicit',()=>{
  assert.equal(taskFailure('invalid_module_source'),'保存结果的完整性校验失败')
  assert.equal(taskPhase({state:'completed',scheduling_state:'inconsistent'}).label,'状态异常，需核查')
  assert.deepEqual(Object.keys(moduleNames),['person_attributes','vehicle_attributes','scene_context'])
})
