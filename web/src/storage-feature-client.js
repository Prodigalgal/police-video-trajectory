export function recordingFeature(snapshot){
  const features=snapshot?.features
  if(snapshot?.schema_version!=='pipeline-features-v1'||!Array.isArray(features)||features.length>32)throw new Error('录像模块配置无效，请重新读取。')
  const matches=features.filter(feature=>feature?.id==='frame_recording')
  if(matches.length!==1)throw new Error('录像模块配置缺失，请重新读取。')
  const feature=matches[0]
  if(typeof feature.enabled!=='boolean'||!Number.isSafeInteger(feature.version)||feature.version<1||feature.default_enabled!==false||!Array.isArray(feature.plugins))throw new Error('录像模块配置不一致。')
  if(feature.plugins.length<1||feature.plugins.length>16||new Set(feature.plugins.map(plugin=>plugin?.id)).size!==feature.plugins.length||!feature.plugins.every(plugin=>['short_segment_v1','context_window_v1'].includes(plugin?.id)&&typeof plugin.label==='string')||!feature.plugins.some(plugin=>plugin.id===feature.plugin))throw new Error('录像策略安装状态不一致。')
  recordingCommand(feature,feature,'00000000-0000-0000-0000-000000000001')
  return feature
}

export function recordingCommand(feature,draft,requestId){
  const parameters=draft.parameters,keys=parameters&&typeof parameters==='object'&&!Array.isArray(parameters)?Object.keys(parameters):null
  if(typeof draft.enabled!=='boolean'||!Number.isSafeInteger(feature.version)||feature.version<1||!keys)throw new Error('录像模块设置无效。')
  if(draft.plugin==='short_segment_v1'&&keys.length===0)return {request_id:requestId,expected_version:feature.version,enabled:draft.enabled,plugin:draft.plugin,parameters:{}}
  if(draft.plugin==='context_window_v1'&&keys.length===1&&keys[0]==='seconds'&&[3,5,10].includes(parameters.seconds))return {request_id:requestId,expected_version:feature.version,enabled:draft.enabled,plugin:draft.plugin,parameters:{seconds:parameters.seconds}}
  throw new Error('请选择已安装的录像策略与有效范围。')
}
