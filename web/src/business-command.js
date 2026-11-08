const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/

// Keep the request identity across an unknown acknowledgement, without persisting the request body.
export async function businessCommand(scope,user,path,body,send,method='POST',signal){
  if(typeof user!=='string'||!user||user.length>100)throw new Error('当前账号信息不完整，请重新登录。')
  const bytes=new TextEncoder().encode(JSON.stringify({path,method,body}))
  const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),value=>value.toString(16).padStart(2,'0')).join('')
  if(signal?.aborted)throw new DOMException('Aborted','AbortError')
  const key=`${scope}:${user}:${digest}`
  let requestId
  try{
    requestId=sessionStorage.getItem(key)
    if(requestId&&!uuid.test(requestId))throw new Error('Invalid command identity')
    if(!requestId){requestId=crypto.randomUUID();sessionStorage.setItem(key,requestId)}
  }catch{throw new Error('浏览器无法保存请求编号，请允许此站点使用会话存储后再提交。')}
  const result=await send(path,{method,signal,body:JSON.stringify({request_id:requestId,...body})})
  try{sessionStorage.removeItem(key)}catch{throw new Error('服务已接受请求，但本地请求记录未能清除，请先刷新查看状态。')}
  return result
}
