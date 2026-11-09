import test from 'node:test'
import assert from 'node:assert/strict'
import {boundedVideoTime,videoWindow} from '../src/video-window.js'
const item={video:{available:true,kind:'session_segment',recording_id:'00000000-0000-0000-0000-000000000001',seek_offset_ms:8000}}
test('前后范围只在同一个已绑定录像内播放，定位始终保留有效帧',()=>{
  assert.deepEqual(videoWindow(item,20),{start:3,end:13,anchor:8,mode:'window',reason:null})
  assert.deepEqual(videoWindow(item,10,10),{start:0,end:10,anchor:8,mode:'window',reason:null})
  assert.equal(videoWindow({...item,video:{...item.video,seek_offset_ms:500}},10).start,0)
  assert.equal(videoWindow(item,20,5,'full').end,20)
})
test('未知、越界和非法时长不能伪造前后视频位置',()=>{
  assert.equal(videoWindow({},10).reason,'position_unavailable')
  assert.equal(videoWindow(item,4).reason,'position_outside_recording')
  assert.equal(videoWindow(item,Infinity),null);assert.equal(videoWindow(item,0),null)
  assert.throws(()=>videoWindow(item,20,60));assert.throws(()=>videoWindow(item,20,5,'other'))
})
test('拖动和播放时间被截在窗口内，原历史录像仍兼容',()=>{
  const window=videoWindow(item,20)
  assert.equal(boundedVideoTime(0,window),3);assert.equal(boundedVideoTime(19,window),13)
  assert.equal(boundedVideoTime(7.5,window),7.5);assert.throws(()=>boundedVideoTime(NaN,window))
  assert.equal(videoWindow({media_offset_ms:25000},60).start,20)
})
