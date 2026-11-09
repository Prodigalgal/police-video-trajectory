export const modelErrors={model_provider_unavailable:'提供商当前不可用，请刷新配置。',model_providers_unavailable:'提供商目录暂不可用。',model_request_conflict:'该请求编号已用于其他修改，请重新操作。',model_profile_conflict:'模型已被其他会话修改，请刷新后再编辑。',model_binding_conflict:'用途绑定已变化，请刷新后重新选择。',model_registry_full:'模型修订数量已达本期上限，请联系管理员整理。',model_profile_unavailable:'该模型修订不存在。',model_activation_forbidden:'该提供商或模型未获准用于所选用途，仅可保存。',invalid_model_request:'模型参数无效，请检查填写内容。',model_control_unavailable:'模型配置服务暂不可用。'}
export const roleLabels={text:'查询理解',vision:'画面分析'}
export function selectionKey(value){return value?`${value.profile_id}:${value.version}`:''}
export function selectionValue(key){if(!key)return null;const [profile_id,version]=key.split(':');return {profile_id,version:Number(version)}}
export function latestProfiles(profiles){const latest=new Map();for(const profile of profiles){const prior=latest.get(profile.profile_id);if(!prior||profile.version>prior.version)latest.set(profile.profile_id,profile)}return [...latest.values()]}
export function appliedConsumers(data,now=Date.now()){
  return data.consumers.filter(value=>value.revision===data.state.revision&&now-new Date(value.seen_at).getTime()<=30000&&now-new Date(value.seen_at).getTime()>=-5000)
}
export function commandRequest(reference,body){const signature=JSON.stringify(body);if(reference.current?.signature!==signature)reference.current={signature,request_id:crypto.randomUUID()};return {...body,request_id:reference.current.request_id}}
