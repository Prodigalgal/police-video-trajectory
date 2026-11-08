import test from 'node:test'
import assert from 'node:assert/strict'
import {validateCandidate,validateCandidatePage,candidateReason,candidateStates} from '../src/specialist-candidate-client.js'
const camera=new Set(['recording-01','recording-04']),id='11111111-1111-4111-8111-111111111111',frame='22222222-2222-4222-8222-222222222222'
const stamp='2026-11-07T08:00:00Z'
function row(state='waiting_budget'){return {candidate_id:id,frame_id:frame,camera_id:'recording-01',target_id:id,state,phase:state,reason:'camera_budget_wait',revision:1,failures:0,can_retry:state==='failed',input_id:null,job_id:null,reference_job_id:null,next_attempt_at:stamp,expires_at:stamp,created_at:stamp,updated_at:stamp}}
function page(){return {enabled:true,claims_enabled:false,items:[row()],has_more:false,next_cursor:null,policy:{pending_limit:500,batch:20,active_limit:1,max_age_seconds:900,max_failures:5}}}
test('candidate states distinguish submission references waits and inference completion',()=>{
  for(const state of Object.keys(candidateStates)){
    const value=row(state)
    if(state==='submitted'){value.job_id=id;value.input_id=id}
    if(state==='reused')value.reference_job_id=id
    assert.equal(validateCandidate(value,camera),value)
  }
  assert.equal(candidateReason('not_known'),'提交条件暂未满足，请核验对应来源与配置')
})
test('a completed submission needs its own input and an original reference stays separate',()=>{
  assert.throws(()=>validateCandidate(row('submitted'),camera))
  assert.throws(()=>validateCandidate({...row('reused'),job_id:id},camera))
  assert.throws(()=>validateCandidate({...row(),job_id:id},camera))
  assert.throws(()=>validateCandidate({...row(),phase:'recognized'},camera))
  assert.throws(()=>validateCandidate({...row(),can_retry:true},camera))
})
test('candidate page validates point state cursor policy and monotonic revision types',()=>{
  assert.equal(validateCandidatePage(page(),camera,{camera:'recording-01',state:'waiting_budget'}).items.length,1)
  for(const patch of [{camera_id:'recording-05'},{revision:true},{revision:0},{reason:'<script>'},{expires_at:'invalid'}])assert.throws(()=>validateCandidate({...row(),...patch},camera))
  assert.throws(()=>validateCandidatePage(page(),camera,{camera:'recording-04'}))
  assert.throws(()=>validateCandidatePage(page(),camera,{state:'failed'}))
  assert.throws(()=>validateCandidatePage(page(),camera,{after:id}))
  assert.throws(()=>validateCandidatePage({...page(),has_more:true,next_cursor:frame},camera))
  assert.throws(()=>validateCandidatePage({...page(),policy:{...page().policy,batch:501}},camera))
})
