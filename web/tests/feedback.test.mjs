import test from 'node:test'
import assert from 'node:assert/strict'
import {feedbackKind,feedbackDuration,popoverPosition} from '../src/feedback.js'

test('error and warning semantics are retained when existing feedback moves into a portal',()=>{
  assert.equal(feedbackKind('notice error','status'),'error')
  assert.equal(feedbackKind('admin-success','status'),'success')
  assert.equal(feedbackKind('ev-unsaved','status'),'warning')
  assert.equal(feedbackKind('','alert'),'error')
  assert.equal(feedbackKind('','status','info'),'info')
})

test('important errors and actionable notices persist while short success feedback can expire',()=>{
  assert.equal(feedbackDuration('error',false),0)
  assert.equal(feedbackDuration('warning',false),0)
  assert.equal(feedbackDuration('info',true),0)
  assert.equal(feedbackDuration('success',true),0)
  assert.equal(feedbackDuration('success',false),5000)
})

test('help popovers prefer available space below and move above near the lower edge',()=>{
  const below=popoverPosition({top:50,bottom:94,right:800},1000,800,200)
  assert.equal(below.top,102);assert.equal(below.left,440)
  const above=popoverPosition({top:720,bottom:764,right:980},1000,800,200)
  assert.equal(above.top,512);assert.ok(above.left+above.width<=988)
})

test('long content and stale anchors stay within a narrow viewport',()=>{
  for(const rect of [{top:170,bottom:214,right:340},{top:800,bottom:844,right:900},{top:-100,bottom:-56,right:0}]){
    const position=popoverPosition(rect,320,240,800)
    assert.ok(position.left>=12&&position.left+position.width<=308)
    assert.ok(position.top>=12&&position.top+position.maxHeight<=228)
  }
})
