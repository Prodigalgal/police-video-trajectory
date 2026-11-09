const token=/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/
const cameraId=/^[A-Za-z0-9_-]{1,100}$/
const conditionId=/^(target|time|location|cameras|plate|upper_color|lower_color|clothing_color|vehicle_color|transport_type|visible_action|carrying:(0|[1-9][0-9]?)|text:(0|[1-9][0-9]?))$/
const text=(value,limit)=>typeof value==='string'&&value.trim().length>0&&value.length<=limit
const boundedList=(value,count,limit)=>Array.isArray(value)&&value.length<=count&&value.every(entry=>text(entry,limit))
const instant=value=>typeof value==='string'&&Number.isFinite(Date.parse(value))

export function canUseKeywordSearch(code,query,failedQuery){
  return ['model_unavailable','invalid_model_plan'].includes(code)&&typeof query==='string'&&typeof failedQuery==='string'&&text(query.trim(),200)&&query.trim()===failedQuery.trim()
}

export function searchRequestBody(query,cursor=null,adjustment=null,mode='natural'){
  if(!text(query,1000)||!['natural','keyword'].includes(mode))throw new Error('检索描述或模式无效。')
  if(mode==='keyword'&&(cursor!==null||adjustment!==null||query.trim().length>200))throw new Error('全文搜索只用于新查询，关键词最多 200 字。')
  return {query:query.trim(),page_size:20,...(cursor?{cursor}:{}),...(adjustment?{adjustment}:{}),...(mode==='keyword'?{mode}:{})}
}

export function validateSearchResult(value,query){
  if(!value||!['ok','clarification_required'].includes(value.status)||(value.query!==undefined&&value.query!==query.trim()))throw new Error('检索返回的查询不一致，请重新搜索。')
  const plan=value.plan
  if(!plan||!['person','vehicle','unknown'].includes(plan.targetType)||!boundedList(plan.cameraIds,50,200)||!boundedList(plan.textTerms,20,200)
    ||!boundedList(plan.clarifications,10,200)||(plan.locationText!==null&&!text(plan.locationText,200))
    ||(plan.start!==null&&!instant(plan.start))||(plan.end!==null&&!instant(plan.end))
    ||(plan.plateNumber!=null&&!text(plan.plateNumber,64)))throw new Error('检索条件返回不完整，请重新搜索。')
  if(plan.attributes!==null){
    const attributes=plan.attributes
    if(!attributes||!boundedList(attributes.carrying,12,100)||['upperColor','lowerColor','clothingColor','vehicleColor','transportType','visibleAction'].some(key=>attributes[key]!=null&&!text(attributes[key],80)))throw new Error('检索特征返回不完整，请重新搜索。')
  }
  if(value.status==='clarification_required'&&!boundedList(value.questions,10,200))throw new Error('澄清问题返回不完整，请重新搜索。')
  if(value.status==='ok'&&(!Array.isArray(value.results)||value.results.length>50||!Number.isSafeInteger(value.total)||value.total<value.results.length))throw new Error('检索列表返回不完整，请重新搜索。')
  if(value.pagination!==undefined){
    const page=value.pagination
    if(!page||!Number.isSafeInteger(page.page)||page.page<1||!Number.isSafeInteger(page.page_size)||page.page_size<1||page.page_size>50
      ||!Number.isSafeInteger(page.total_pages)||page.total_pages!==Math.ceil(value.total/page.page_size)||page.page>Math.max(1,page.total_pages)
      ||!instant(page.expires_at)||['first_cursor','close_cursor'].some(key=>!text(page[key],32768)||!token.test(page[key]))
      ||['previous_cursor','next_cursor'].some(key=>page[key]!==null&&(!text(page[key],32768)||!token.test(page[key]))))throw new Error('翻页记录返回不完整，请重新搜索。')
  }
  if(value.guidance!==undefined)validateGuidance(value.guidance)
  return {...value,query:query.trim()}
}

export function validateGuidance(value){
  if(!value||value.schema_version!=='search-guidance-v1'||(value.context!==null&&(!text(value.context,32768)||!token.test(value.context)))||!instant(value.expires_at)
    ||!['ready','no_matches','plan_incomplete','location_unresolved','conditions_required'].includes(value.reason)
    ||!Array.isArray(value.conditions)||value.conditions.length>43||!Array.isArray(value.camera_choices)||value.camera_choices.length>50)throw new Error('可调整的检索条件返回不完整，请重新搜索。')
  const conditions=new Set(),cameras=new Set()
  for(const condition of value.conditions){
    if(!condition||!conditionId.test(condition.id)||!text(condition.label,12000)||typeof condition.removable!=='boolean'||conditions.has(condition.id))throw new Error('检索条件记录重复或无效，请重新搜索。')
    conditions.add(condition.id)
  }
  for(const camera of value.camera_choices){
    if(!camera||!cameraId.test(camera.camera_id)||!text(camera.label,200)||cameras.has(camera.camera_id))throw new Error('点位记录重复或无效，请重新搜索。')
    cameras.add(camera.camera_id)
  }
  return value
}

export function supplementQuery(original,answer){
  if(!text(original,1000)||!text(answer,500))throw new Error('请填写补充信息，最多 500 字。')
  const combined=original.trim()+'；补充：'+answer.trim()
  if(combined.length>1000)throw new Error('合并后的描述超过 1000 字，请缩短补充内容或直接修改顶部搜索框。')
  return combined
}

export function removeSearchCondition(guidance,id){
  validateGuidance(guidance)
  if(guidance.context===null)throw new Error('条件较多，请修改原描述后重新搜索。')
  if(!guidance.conditions.some(condition=>condition.id===id&&condition.removable))throw new Error('该条件不可直接移除，请修改搜索描述。')
  return {context:guidance.context,action:'remove',condition:id}
}

export function chooseSearchCameras(guidance,ids){
  validateGuidance(guidance)
  if(guidance.context===null)throw new Error('条件较多，请修改原描述后重新搜索。')
  if(!Array.isArray(ids)||!ids.length||ids.length>50||new Set(ids).size!==ids.length||ids.some(id=>!guidance.camera_choices.some(camera=>camera.camera_id===id)))throw new Error('请从列表选择有效点位。')
  return {context:guidance.context,action:'choose_cameras',camera_ids:[...ids].sort()}
}
