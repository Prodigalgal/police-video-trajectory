const schema='map-document-v1'
const identity=/^[A-Za-z0-9_-]{1,100}$/
const coordinate=(value,bound)=>typeof value==='number'&&Number.isFinite(value)&&Math.abs(value)<=bound
const label=value=>typeof value==='string'?value.slice(0,200):''

export function mapDocumentView(points,links,pick){
  return {points:points.slice(0,200).filter(point=>identity.test(point.observation_id||point.camera_id)&&coordinate(point.longitude,180)&&coordinate(point.latitude,90)).map(point=>({
    id:point.observation_id||point.camera_id,camera_id:point.camera_id,camera_name:label(point.camera_name||point.display_name||point.camera_id),longitude:point.longitude,latitude:point.latitude
  })),links:links.slice(0,400).map(link=>({from_observation_id:link.from_observation_id,to_observation_id:link.to_observation_id,review_state:link.review_state,review_stale:link.review_stale===true})),pick:pick===true}
}

export function validMapReply(value,nonce){
  if(!value||value.schema!==schema||value.nonce!==nonce)return false
  if(['ready','loaded'].includes(value.kind))return true
  if(value.kind==='error')return ['configuration','resource'].includes(value.code)
  if(value.kind==='select')return typeof value.id==='string'&&identity.test(value.id)
  return value.kind==='pick'&&coordinate(value.longitude,180)&&coordinate(value.latitude,90)
}

export function validMapView(value){
  return value&&Array.isArray(value.points)&&value.points.length<=200&&Array.isArray(value.links)&&value.links.length<=400&&typeof value.pick==='boolean'
    &&value.points.every(point=>point&&identity.test(point.id)&&identity.test(point.camera_id)&&typeof point.camera_name==='string'&&point.camera_name.length<=200&&coordinate(point.longitude,180)&&coordinate(point.latitude,90))
    &&value.links.every(link=>link&&identity.test(link.from_observation_id)&&identity.test(link.to_observation_id)&&['pending','accepted','rejected'].includes(link.review_state)&&typeof link.review_stale==='boolean')
}
