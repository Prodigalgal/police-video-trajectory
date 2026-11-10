const kinds=['processing','appearance'],roles={processing:['license_plate','text_ocr'],appearance:['appearance']}
const digest=/^[a-f0-9]{64}$/
export const localModelErrors={model_binding_revision_conflict:'模型绑定已被更新，请刷新后重试。',model_catalog_source_changed:'登记目录已更新，请核对当前可选配置后重新保存。',model_profile_unavailable:'所选配置未登记或不支持该用途。',command_request_conflict:'本次请求内容发生变化，请刷新后重试。',model_catalog_unavailable:'模型目录暂不可读取，请稍后刷新。'}
export function localCatalogs(value){
  if(value?.schema_version!=='local-model-bindings-v1'||!Array.isArray(value.catalogs)||value.catalogs.length!==2||new Set(value.catalogs.map(catalog=>catalog?.id)).size!==2)throw new Error('模型目录响应无效。')
  for(const catalog of value.catalogs){
    if(!kinds.includes(catalog.id)||typeof catalog.available!=='boolean')throw new Error('模型目录类型无效。')
    if(!catalog.available)continue
    if(!Number.isSafeInteger(catalog.revision)||catalog.revision<0||!digest.test(catalog.base_sha256)||typeof catalog.source_current!=='boolean'||!Array.isArray(catalog.profiles)||catalog.profiles.length>32||!catalog.bindings||typeof catalog.bindings!=='object'||Object.keys(catalog.bindings).sort().join(',')!==[...roles[catalog.id]].sort().join(','))throw new Error('模型目录版本或绑定无效。')
    const identities=new Set()
    for(const profile of catalog.profiles){
      if(!profile||typeof profile.profile!=='string'||!digest.test(profile.sha256)||identities.has(profile.profile)||catalog.id==='processing'&&(!Array.isArray(profile.capabilities)||!profile.capabilities.every(role=>roles.processing.includes(role)))||catalog.id==='appearance'&&![384,512].includes(profile.dimensions))throw new Error('已登记模型配置不一致。')
      identities.add(profile.profile)
    }
    if(catalog.source_current)bindingCommand(catalog,catalog.bindings,'contract')
  }
  return value.catalogs
}
export function profileLabel(profile,kind){return kind==='appearance'?`外观特征配置（${profile.dimensions} 维） · ${profile.profile}`:`专用识别配置 · ${profile.profile}`}
export function bindingCommand(catalog,bindings,requestId){
  if(!catalog.available||Object.keys(bindings).sort().join(',')!==[...roles[catalog.id]].sort().join(','))throw new Error('请先读取当前模型目录。')
  for(const role of roles[catalog.id]){
    const id=bindings[role]
    if(id===null&&catalog.id==='processing')continue
    const profile=catalog.profiles.find(profile=>profile.profile===id)
    if(!profile||catalog.id==='processing'&&!profile.capabilities.includes(role))throw new Error('请选择当前已登记且支持该用途的配置。')
  }
  return {request_id:requestId,expected_revision:catalog.revision,base_sha256:catalog.base_sha256,bindings:{...bindings}}
}
