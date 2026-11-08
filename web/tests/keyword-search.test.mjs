import test from 'node:test'
import assert from 'node:assert/strict'
import {canUseKeywordSearch,searchRequestBody} from '../src/search-guidance.js'

test('keyword fallback is offered only for the unchanged failed description and model errors',()=>{
  assert.equal(canUseKeywordSearch('model_unavailable','白色汽车','白色汽车'),true)
  assert.equal(canUseKeywordSearch('invalid_model_plan','白色汽车','白色汽车'),true)
  assert.equal(canUseKeywordSearch('search_backend_unavailable','白色汽车','白色汽车'),false)
  assert.equal(canUseKeywordSearch('model_unavailable','昨天的车','白色汽车'),false)
  assert.equal(canUseKeywordSearch('model_unavailable','车'.repeat(201),'车'.repeat(201)),false)
  assert.equal(canUseKeywordSearch('model_unavailable',null,null),false)
})

test('new keyword intent is explicit while paging and refinement continue the signed plan',()=>{
  assert.deepEqual(searchRequestBody(' 白色汽车 ',null,null,'keyword'),{query:'白色汽车',page_size:20,mode:'keyword'})
  assert.deepEqual(searchRequestBody('白色汽车','signed-cursor'),{query:'白色汽车',page_size:20,cursor:'signed-cursor'})
  const adjustment={context:'signed-context',action:'choose_cameras',camera_ids:['recording-01']}
  assert.deepEqual(searchRequestBody('白色汽车',null,adjustment),{query:'白色汽车',page_size:20,adjustment})
  assert.throws(()=>searchRequestBody('车','cursor',null,'keyword'))
  assert.throws(()=>searchRequestBody('车',null,adjustment,'keyword'))
  assert.throws(()=>searchRequestBody('车'.repeat(201),null,null,'keyword'))
  assert.throws(()=>searchRequestBody('车',null,null,'dsl'))
})
