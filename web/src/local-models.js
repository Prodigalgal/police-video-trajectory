const kinds=['processing','appearance'],roles={processing:['license_plate','text_ocr'],appearance:['appearance']}
const digest=/^[a-f0-9]{64}$/
const dimensions=new Set([128,256,384,512,768,1024])
function bindingRoles(kind,bindings){
  const keys=Object.keys(bindings||{}).sort().join(',')
  if(kind==='appearance'&&keys==='appearance,person,vehicle')return ['appearance','person','vehicle']
  return keys===[...(roles[kind]||[])].sort().join(',')?roles[kind]:null
}
export function profileSupports(profile,role){
  const targets=profile.target_types||['person','vehicle']
  return role==='appearance'?targets.includes('person')&&targets.includes('vehicle'):targets.includes(role)
}
export function draftBindings(catalog){return catalog.id==='appearance'?{appearance:catalog.bindings.appearance,person:catalog.bindings.person||catalog.bindings.appearance,vehicle:catalog.bindings.vehicle||catalog.bindings.appearance}:{...catalog.bindings}}
export const localModelErrors={appearance_target_type_unsupported:'所选模型不支持当前目标类型。',model_binding_revision_conflict:'模型绑定已被更新，请刷新后重试。',model_catalog_source_changed:'登记目录已更新，请核对当前可选配置后重新保存。',model_profile_unavailable:'所选配置未登记或不支持该用途。',appearance_model_not_installed:'所选外观模型尚未安装或权重校验失败，暂不能启用。',command_request_conflict:'本次请求内容发生变化，请刷新后重试。',model_catalog_unavailable:'模型目录暂不可读取，请稍后刷新。'}
export function localCatalogs(value){
  if(value?.schema_version!=='local-model-bindings-v1'||!Array.isArray(value.catalogs)||value.catalogs.length!==2||new Set(value.catalogs.map(catalog=>catalog?.id)).size!==2)throw new Error('模型目录响应无效。')
  for(const catalog of value.catalogs){
    if(!kinds.includes(catalog.id)||typeof catalog.available!=='boolean')throw new Error('模型目录类型无效。')
    if(!catalog.available)continue
    if(!Number.isSafeInteger(catalog.revision)||catalog.revision<0||!digest.test(catalog.base_sha256)||typeof catalog.source_current!=='boolean'||!Array.isArray(catalog.profiles)||catalog.profiles.length>32||!catalog.bindings||typeof catalog.bindings!=='object'||!bindingRoles(catalog.id,catalog.bindings))throw new Error('模型目录版本或绑定无效。')
    const identities=new Set()
    for(const profile of catalog.profiles){
      if(!profile||typeof profile.profile!=='string'||!digest.test(profile.sha256)||identities.has(profile.profile)||catalog.id==='processing'&&(!Array.isArray(profile.capabilities)||!profile.capabilities.every(role=>roles.processing.includes(role)))||catalog.id==='appearance'&&(!dimensions.has(profile.dimensions)||profile.target_types!==undefined&&(!Array.isArray(profile.target_types)||!profile.target_types.length||new Set(profile.target_types).size!==profile.target_types.length||profile.target_types.some(type=>!['person','vehicle'].includes(type)))))throw new Error('已登记模型配置不一致。')
      identities.add(profile.profile)
    }
    if(catalog.source_current)bindingCommand(catalog,catalog.bindings,'contract',{requireInstalled:false})
  }
  return value.catalogs
}
export function profileLabel(profile,kind){return kind==='appearance'?`外观特征配置（${profile.dimensions} 维） · ${profile.profile}`:`专用识别配置 · ${profile.profile}`}
export function bindingCommand(catalog,bindings,requestId,{requireInstalled=true}={}){
  const selectedRoles=bindingRoles(catalog.id,bindings)
  if(!catalog.available||!selectedRoles)throw new Error('请先读取当前模型目录。')
  for(const role of selectedRoles){
    const id=bindings[role]
    if(id===null&&catalog.id==='processing')continue
    const profile=catalog.profiles.find(profile=>profile.profile===id)
    if(!profile||catalog.id==='processing'&&!profile.capabilities.includes(role)||catalog.id==='appearance'&&!profileSupports(profile,role))throw new Error('请选择当前已登记且支持该用途的配置。')
    if(catalog.id==='appearance'&&requireInstalled&&profile.installed!==true)throw new Error(localModelErrors.appearance_model_not_installed)
  }
  return {request_id:requestId,expected_revision:catalog.revision,base_sha256:catalog.base_sha256,bindings:{...bindings}}
}
