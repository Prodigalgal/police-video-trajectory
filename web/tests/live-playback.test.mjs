import test from 'node:test'
import assert from 'node:assert/strict'
import {bufferedPlaybackTarget,liveSource,liveConfiguration} from '../src/live-playback.js'

test('首次直播位置超过缓冲区时定位到已下载画面，不追向尚未下载的时间',()=>{
  assert.equal(bufferedPlaybackTarget([[0,5]],14.095,14),4.9)
  assert.equal(bufferedPlaybackTarget([[0,5]],2,14),null)
  assert.equal(bufferedPlaybackTarget([],14,14),null)
  const selected=bufferedPlaybackTarget([[0,2],[5,7]],3,6)
  assert.equal(selected,6)
})
test('预览和原画使用独立受控路径，参数不能变成外部上游地址',()=>{
  assert.equal(liveSource('camera-1','preview'),'/api/v1/live/cameras/camera-1/preview/index.m3u8')
  assert.equal(liveConfiguration('preview').liveSyncDurationCount,2)
  assert.ok(liveConfiguration('hls').fragLoadingTimeOut>liveConfiguration('preview').fragLoadingTimeOut)
  assert.throws(()=>liveSource('../private','preview'))
  assert.throws(()=>liveSource('camera-1','https://example.invalid'))
})
