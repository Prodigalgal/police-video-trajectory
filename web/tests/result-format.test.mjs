import test from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { evidenceBox,formatOffset,sourceStateLabel,verifyEvidenceDigest } from '../src/result-format.js'

test('target overlays require matching pixels, source digest, dimensions and an explicit detector binding', () => {
  const detail={ evidence:{width:1280,height:720,capture_integrity:'matched'}, target_location:{status:'bound_detection',coordinate_space:'jpeg_pixels',image_width:1280,image_height:720,bbox_xyxy:[100,120,200,260]} }
  assert.deepEqual(evidenceBox(detail,{width:1280,height:720},'valid'),{x:100,y:120,width:100,height:140})
  assert.equal(evidenceBox(detail,{width:720,height:1280},'valid'),null)
  assert.equal(evidenceBox(detail,{width:1280,height:720},'unavailable'),null)
  assert.equal(evidenceBox({...detail,target_location:{...detail.target_location,status:'unavailable'}},{width:1280,height:720},'valid'),null)
  assert.equal(evidenceBox({...detail,evidence:{...detail.evidence,capture_integrity:'legacy_without_digest'}},{width:1280,height:720},'valid'),null)
})
test('unknown media offsets and paused sources never become playback positions or online states', () => {
  assert.equal(formatOffset(null),'位置未知');assert.equal(formatOffset(-1),'位置未知');assert.equal(formatOffset(3661000),'01:01:01')
  assert.equal(sourceStateLabel({enabled:false,task_active:true}),'已暂停')
  assert.equal(sourceStateLabel({enabled:true,processing_state:'leased'}),'任务已领取')
})
test('image consistency checks reject wrong or malformed digests and report unsupported crypto explicitly', async () => {
  const bytes=new Uint8Array([1,2,3]).buffer
  const hash=Buffer.from(await webcrypto.subtle.digest('SHA-256',bytes)).toString('hex')
  assert.equal(await verifyEvidenceDigest(bytes,hash,webcrypto),'valid')
  await assert.rejects(verifyEvidenceDigest(bytes,'a'.repeat(64),webcrypto),/不一致/)
  await assert.rejects(verifyEvidenceDigest(bytes,'invalid',webcrypto),/无效/)
  assert.equal(await verifyEvidenceDigest(bytes,hash,{}),'unavailable')
})
