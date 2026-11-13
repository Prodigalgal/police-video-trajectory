function featureState(snapshot,identity){
  const features=snapshot?.features
  if(snapshot?.schema_version!=='pipeline-features-v1'||!Array.isArray(features)||features.length>32)throw new Error('模块配置无效，请重新读取。')
  const matches=features.filter(feature=>feature?.id===identity)
  if(matches.length!==1)throw new Error('模块配置缺失，请重新读取。')
  const feature=matches[0]
  return feature
}

export function exportFeature(snapshot){
  const feature=featureState(snapshot,'evidence_export')
  if(typeof feature.installed!=='boolean'||feature.default_enabled!==false||feature.apply_scope!=='new_exports'||!Array.isArray(feature.plugins)||feature.plugins.length!==1||feature.plugins[0]?.id!=='bound_video_package_v1'||typeof feature.plugins[0]?.label!=='string')throw new Error('视频证据包模块配置无效，请重新读取。')
  exportFeatureCommand({...feature,installed:true},feature)
  return feature
}
export function exportFeatureCommand(feature,draft){
  if(!Number.isSafeInteger(feature.version)||feature.version<1||typeof draft.enabled!=='boolean'||draft.plugin!=='bound_video_package_v1'||!draft.parameters||Array.isArray(draft.parameters)||Object.keys(draft.parameters).length||draft.enabled&&!feature.installed)throw new Error('请选择已安装的视频证据包模块。')
  return {expected_version:feature.version,enabled:draft.enabled,plugin:draft.plugin,parameters:{}}
}
