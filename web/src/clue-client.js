import {chineseMessage} from './chinese-copy.js'
const messages={collection_unavailable:'线索集已不可用。',collection_revision_conflict:'清单已有更新，请刷新后再操作。',collection_request_conflict:'创建请求已变化，请重新打开表单。',collection_closed:'清单已归档，请先重新打开。',collection_full:'当前清单已达到容量上限，请另建线索集。',observation_unavailable:'该线索已不可用。',observation_revision_conflict:'线索已有新版本，请重新打开详情核验。',review_evidence_changed:'关键帧已变化，请重新读取后复核。',collection_export_too_large:'当前清单超过导出大小限制，请分成较小的线索集。',item_unavailable:'线索不在当前清单中。',invalid_clue_request:'请检查填写内容。'}
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
function validHead(value){return value&&typeof value.collection_id==='string'&&uuid.test(value.collection_id)&&typeof value.title==='string'&&value.title.trim().length>0&&value.title.length<=160&&Number.isSafeInteger(value.revision)&&value.revision>0&&['open','closed'].includes(value.state)}

export function validateCollection(data,expectedId=null){
  const fail=()=>{throw new Error('线索集记录不一致，请刷新后重试。')}
  if(!validHead(data?.collection)||expectedId&&data.collection.collection_id!==expectedId||!Array.isArray(data.nodes)||data.nodes.length>200||!Array.isArray(data.links)||data.links.length>400||data.association_kind!=='candidate_only'||!['collection_order','verified_source_time'].includes(data.timeline_order))fail()
  const ids=new Set()
  for(const node of data.nodes){
    if(!node||typeof node.observation_id!=='string'||!uuid.test(node.observation_id)||ids.has(node.observation_id)||typeof node.available!=='boolean'||typeof node.review_stale!=='boolean'||!Number.isSafeInteger(node.record_version)||node.record_version<1||!['pending','retained','excluded'].includes(node.review_state)||node.available&&(typeof node.frame_id!=='string'||!uuid.test(node.frame_id)||!['person','vehicle'].includes(node.target_type)))fail()
    ids.add(node.observation_id)
  }
  const pairs=new Set()
  for(const edge of data.links){
    if(!edge||!ids.has(edge.from_observation_id)||!ids.has(edge.to_observation_id)||edge.from_observation_id===edge.to_observation_id||!['pending','accepted','rejected'].includes(edge.review_state)||typeof edge.review_stale!=='boolean'||typeof edge.reason!=='string'||!edge.reason.trim()||edge.reason.length>1000)fail()
    const pair=edge.from_observation_id+':'+edge.to_observation_id
    if(pairs.has(pair))fail()
    pairs.add(pair)
  }
  if(data.timeline_order==='verified_source_time'){
    if(!data.nodes.length||data.nodes.some(node=>!node.available||node.source_time_origin!=='verified'||typeof node.source_time!=='string'||!Number.isFinite(Date.parse(node.source_time))))fail()
    if(data.nodes.some((node,index)=>index>0&&Date.parse(node.source_time)<Date.parse(data.nodes[index-1].source_time)))fail()
  }
  return data
}

export async function clueApi(path='',options={}) {
  const response=await fetch('/api/v1/collections'+path,{...options,cache:'no-store',headers:{'Content-Type':'application/json',...(options.headers||{})}})
  let data={};try{data=await response.json()}catch{/* bounded generic error */}
  if(!response.ok)throw Object.assign(new Error(messages[data.code]||chineseMessage(data.detail,'线索整理服务暂不可用，请稍后重试。')),{code:data.code,unauthorized:response.status===401})
  if(Array.isArray(data.items)){
    const cursorValid=data.has_more?typeof data.next_cursor==='string'&&uuid.test(data.next_cursor):data.next_cursor===null
    if(data.items.length>50||!data.items.every(validHead)||new Set(data.items.map(item=>item.collection_id)).size!==data.items.length||typeof data.has_more!=='boolean'||!cursorValid)throw new Error('清单分页数据无效，请刷新重试。')
  }else validateCollection(data,/^\/([a-f0-9-]{36})(?:\/|$)/.exec(path)?.[1]||null)
  return data
}

export async function downloadCollection(id,revision,format) {
  if(typeof id!=='string'||!uuid.test(id)||!Number.isSafeInteger(revision)||revision<1||!['csv','json'].includes(format))throw new Error('导出参数无效，请刷新重试。')
  const response=await fetch(`/api/v1/collections/${id}/export?revision=${revision}&format=${format}`,{cache:'no-store'})
  if(!response.ok){let data={};try{data=await response.json()}catch{};throw Object.assign(new Error(messages[data.code]||'导出失败，请刷新后重试。'),{unauthorized:response.status===401})}
  if(!response.body||!globalThis.crypto?.subtle)throw new Error('当前浏览器无法校验导出文件。')
  const reader=response.body.getReader(),chunks=[];let size=0
  try{
    while(true){const chunk=await reader.read();if(chunk.done)break;size+=chunk.value.byteLength;if(size>2*1024*1024){await reader.cancel();throw new Error('导出文件超过大小限制。')}chunks.push(chunk.value)}
  }finally{reader.releaseLock()}
  if(!size)throw new Error('导出文件为空，请重试。')
  const bytes=new Uint8Array(size);let offset=0
  for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.byteLength}
  const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),v=>v.toString(16).padStart(2,'0')).join('')
  if(digest!==response.headers.get('x-content-sha256'))throw new Error('导出文件校验失败，请重试。')
  const url=URL.createObjectURL(new Blob([bytes],{type:format==='csv'?'text/csv;charset=utf-8':'application/json'}))
  const link=document.createElement('a');link.href=url;link.download=`clues-${id}-r${revision}.${format}`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
}

export const reviewNames={pending:'待复核',retained:'保留线索',excluded:'已排除'}
export const linkNames={pending:'待复核',accepted:'保留关联',rejected:'排除关联'}

export function nodeLabel(node){return node?`${node.camera_name||node.camera_id} · ${node.target_type==='person'?'人物':'车辆'} · ${node.observation_id.slice(0,8)}`:'不可用的线索'}
