import test from 'node:test'
import assert from 'node:assert/strict'
import {releaseDetachedVideo} from '../src/media-playback.js'

test('开发模式重复挂载保留播放地址，关闭或重试移除旧节点时才释放资源',()=>{
  const calls=[],video={isConnected:true,pause:()=>calls.push('pause'),removeAttribute:name=>calls.push(['remove',name]),load:()=>calls.push('load')}
  releaseDetachedVideo(video)
  releaseDetachedVideo(video)
  assert.deepEqual(calls,[])
  video.isConnected=false
  releaseDetachedVideo(video)
  assert.deepEqual(calls,['pause',['remove','src'],'load'])
  releaseDetachedVideo(null)
})
