export function feedbackKind(className='',role='status',kind){
  if(['error','warning','success','info'].includes(kind))return kind
  if(/error/.test(className)||role==='alert')return 'error'
  if(/unsaved|warning/.test(className))return 'warning'
  if(/success/.test(className))return 'success'
  return 'info'
}

export function feedbackDuration(kind,hasActions){
  return !hasActions&&['success','info'].includes(kind)?5000:0
}

export function popoverPosition(rect,viewportWidth,viewportHeight,height=260){
  const margin=12,gap=8,width=Math.min(360,Math.max(0,viewportWidth-margin*2)),maxHeight=Math.max(0,viewportHeight-margin*2)
  const actualHeight=Math.min(height,maxHeight)
  const left=Math.min(Math.max(margin,rect.right-width),Math.max(margin,viewportWidth-margin-width))
  const below=rect.bottom+gap,candidate=below+actualHeight<=viewportHeight-margin?below:rect.top-gap-actualHeight
  const top=Math.min(Math.max(margin,candidate),Math.max(margin,viewportHeight-margin-actualHeight))
  return {left,top,width,maxHeight}
}
