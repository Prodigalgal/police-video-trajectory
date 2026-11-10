const sha=/^[a-f0-9]{64}$/
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const roles=['text','vision']
export const catalogFailureNames={catalog_busy:'目录核验正在进行',credential_rejected:'上游拒绝凭据',catalog_rate_limited:'上游限制请求频率',catalog_not_found:'上游没有模型目录接口',catalog_unavailable:'上游目录暂不可用',configuration_changed:'提供商配置已变化',catalog_timeout:'上游目录读取超时',catalog_network_unavailable:'上游连接暂不可用',catalog_invalid:'上游目录格式不兼容',credential_unavailable:'服务端凭据暂不可读'}
const strings=(values,allowed)=>Array.isArray(values)&&values.length<=allowed.length&&new Set(values).size===values.length&&values.every(value=>allowed.includes(value))
export function validateProviderCatalog(value,provider){
  const invalid=()=>{throw new Error('模型目录与提供商配置不一致，请刷新后重试。')}
  if(!value||value.schema_version!=='provider-model-catalog-v1'||value.provider!==provider.id||!sha.test(value.configuration_sha256||'')||!sha.test(value.catalog_sha256||'')||typeof value.cached!=='boolean'||value.inference_verified!==false||!Number.isFinite(Date.parse(value.checked_at))||!['ready','unavailable'].includes(value.state)||!Array.isArray(value.models)||value.models.length>512)invalid()
  const seen=new Set()
  for(const model of value.models){
    if(!model||typeof model.id!=='string'||!/^[A-Za-z0-9][A-Za-z0-9._:/+@() -]{0,149}$/.test(model.id)||seen.has(model.id)||typeof model.name!=='string'||!model.name||model.name.length>100||!strings(model.declared_roles,roles)||!strings(model.allowed_roles,provider.roles)||!model.allowed_roles.every(role=>model.declared_roles.includes(role))||!strings(model.protocols,provider.protocols)||!strings(model.input_media,['text','image','audio','video','file'])||!strings(model.reasoning_levels,['auto','none','minimal','low','medium','high','xhigh','max'])||model.streaming!==null&&typeof model.streaming!=='boolean'||model.tools!==null&&typeof model.tools!=='boolean'||model.inference_verified!==false||model.analysis_reason!==null&&model.analysis_reason!=='media_endpoint_required')invalid()
    if(model.allowed_roles.length&&(model.streaming!==true||!model.protocols.length||model.analysis_reason!==null))invalid()
    seen.add(model.id)
  }
  if(value.state==='ready'?value.diagnostics!==null:value.models.length||!value.diagnostics||!Object.hasOwn(catalogFailureNames,value.diagnostics.category))invalid()
  if(value.diagnostics){
    const d=value.diagnostics
    if(Object.keys(d).some(key=>!['category','upstream_status','upstream_request_id','upstream_error_code'].includes(key))||d.upstream_status!==undefined&&(!Number.isSafeInteger(d.upstream_status)||d.upstream_status<100||d.upstream_status>599)||d.upstream_request_id!==undefined&&!uuid.test(d.upstream_request_id)||d.upstream_error_code!==undefined&&(typeof d.upstream_error_code!=='string'||!/^[a-z_]{1,80}$/.test(d.upstream_error_code)))invalid()
  }
  return value
}
export function chooseCatalogModel(form,model){
  if(!model.allowed_roles.length||model.streaming!==true||!model.protocols.length||model.analysis_reason)throw new Error('该模型暂不适用于本系统的分析用途。')
  return {...form,model:model.id,display_name:form.display_name||model.name,roles:[...model.allowed_roles],protocol:model.protocols.includes(form.protocol)?form.protocol:model.protocols[0],
    reasoning_effort:model.reasoning_levels.includes(form.reasoning_effort)?form.reasoning_effort:'',output_mode:form.output_mode==='tool'&&model.tools===true?'tool':'text',json_mode:false,
    image_transport:model.allowed_roles.includes('vision')?form.image_transport:'inline'}
}
export function providerCatalogPath(provider,refresh=false){
  if(typeof provider!=='string'||!/^[a-z][a-z0-9_-]{0,79}$/.test(provider))throw new Error('提供商标识无效。')
  return `models/providers/${encodeURIComponent(provider)}/models`+(refresh?'/refresh':'')
}
