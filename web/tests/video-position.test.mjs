import {test} from 'node:test'
import assert from 'node:assert/strict'
import {videoOffsetMs} from '../src/video-position.js'

test('same-session positions take precedence without inventing an archive offset', () => {
  const video = {available:true,kind:'session_segment',recording_id:'00000000-0000-0000-0000-000000000001',seek_offset_ms:1234}
  assert.equal(videoOffsetMs({video,media_offset_ms:90000}),1234)
  for(const value of [-1,NaN,Infinity,20001,1.2,'1234',null]) assert.equal(videoOffsetMs({video:{...video,seek_offset_ms:value},media_offset_ms:90000}),null)
  assert.equal(videoOffsetMs({video:{...video,available:false}}),null)
  assert.equal(videoOffsetMs({video:{...video,recording_id:'../elsewhere'}}),null)
})

test('existing archive positions and unknown times retain their meanings', () => {
  assert.equal(videoOffsetMs({media_offset_ms:0}),0)
  assert.equal(videoOffsetMs({media_offset_ms:90000}),90000)
  assert.equal(videoOffsetMs({media_offset_ms:null}),null)
  assert.equal(videoOffsetMs({media_offset_ms:-2}),null)
  assert.equal(videoOffsetMs({source_type:'rtsp'}),null)
})
