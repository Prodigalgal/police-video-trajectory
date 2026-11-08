import test from 'node:test'
import assert from 'node:assert/strict'
import {readdirSync,readFileSync} from 'node:fs'
import {parse} from '@babel/parser'
import {clueApi} from '../src/clue-client.js'
import {businessApi} from '../src/investigation-client.js'
import {strategyLabel} from '../src/chinese-copy.js'

test('页面固定文案与无障碍提示使用中文，保留真实模型名称',()=>{
  const failures=[]
  for(const file of readdirSync(new URL('../src/',import.meta.url)).filter(name=>name.endsWith('.jsx'))){
    const code=readFileSync(new URL('../src/'+file,import.meta.url),'utf8'),tree=parse(code,{sourceType:'module',plugins:['jsx']})
    function inspect(node){
      if(!node||typeof node!=='object')return
      const visible=node.type==='JSXText'?node.value:node.type==='JSXAttribute'&&['aria-label','title','placeholder','alt'].includes(node.name.name)&&node.value?.type==='StringLiteral'?node.value.value:null
      if(visible&&/[A-Za-z]/.test(visible.replace(/NanoDet|YOLOX/g,'')))failures.push(file+':'+node.loc.start.line+':'+visible.trim())
      for(const[key,value]of Object.entries(node)){
        if(key==='loc')continue
        if(Array.isArray(value))value.forEach(inspect);else if(value&&typeof value==='object')inspect(value)
      }
    }
    inspect(tree)
  }
  assert.deepEqual(failures,[])
})

test('上游英文错误正文不进入业务和线索页面，已知错误保持中文说明',async()=>{
  const original=globalThis.fetch
  try{
    globalThis.fetch=async()=>({ok:false,status:503,json:async()=>({code:'unknown_new_error',detail:'Internal server error: database unavailable'})})
    await assert.rejects(clueApi(),/线索整理服务暂不可用/)
    await assert.rejects(businessApi('/api/v1/test'),/业务服务暂不可用/)
    globalThis.fetch=async()=>({ok:false,status:409,json:async()=>({code:'collection_revision_conflict',detail:'Conflict'})})
    await assert.rejects(clueApi(),/清单已有更新/)
  }finally{globalThis.fetch=original}
  assert.equal(strategyLabel('local-track-segments-v1'),'同镜头连续片段策略')
  assert.equal(strategyLabel('new-plugin-v2'),'其他处理策略')
})
