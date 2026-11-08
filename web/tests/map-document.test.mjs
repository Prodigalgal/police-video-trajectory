import test from 'node:test'
import assert from 'node:assert/strict'
import {mapDocumentView,validMapView,validMapReply} from '../src/maps/map-document.js'

const nonce='282d9b29-404a-42ab-a166-3bb4698be87a'
const point={camera_id:'recording-01',observation_id:'9a6b7dd4-d158-4303-9dda-d72f0fb551d1',camera_name:'点位 01',longitude:117.3558,latitude:24.5163}
test('map messages carry only bounded display fields and retain the selected identity',()=>{
  const view=mapDocumentView([{...point,description:'not needed by SDK',model_payload:{private:true}}],[],true)
  assert.equal(validMapView(view),true)
  assert.equal(view.points[0].id,point.observation_id)
  assert.equal('description' in view.points[0],false)
  assert.equal('model_payload' in view.points[0],false)
  assert.equal(mapDocumentView(Array(201).fill(point),[],false).points.length,200)
})
test('stale frame messages and invalid coordinates cannot become parent selections',()=>{
  const reply={schema:'map-document-v1',nonce,kind:'pick',longitude:117,latitude:24}
  assert.equal(validMapReply(reply,nonce),true)
  assert.equal(validMapReply({...reply,nonce:'stale'},nonce),false)
  assert.equal(validMapReply({...reply,longitude:Infinity},nonce),false)
  assert.equal(validMapReply({...reply,latitude:91},nonce),false)
  assert.equal(validMapReply({...reply,kind:'run-script'},nonce),false)
  const view=mapDocumentView([point],[],false)
  assert.equal(validMapView({...view,points:[{...view.points[0],longitude:NaN}]}),false)
  assert.equal(validMapView({...view,points:Array(201).fill(view.points[0])}),false)
  assert.equal(validMapView({...view,links:[{from_observation_id:'../bad'}]}),false)
})
