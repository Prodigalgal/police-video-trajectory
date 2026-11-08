const defaultMessage='操作暂未完成，请稍后重试。'
const strategyNames={'local-track-segments-v1':'同镜头连续片段策略','attribute-time-candidates-v1':'属性与时间候选关联策略'}

export function strategyLabel(value){return strategyNames[value]||'其他处理策略'}

export function chineseMessage(value,fallback=defaultMessage){
  const message=typeof value==='string'?value:value?.message
  if(typeof message!=='string'||!message.trim())return fallback
  if(/failed to fetch|network ?error|load failed|network request failed/i.test(message))return '网络连接失败，请检查连接后重试。'
  if(/timeout|timed out/i.test(message))return '请求超时，请稍后重试。'
  if(/abort(?:ed|error)|operation was cancelled/i.test(message))return '操作已取消，可重新尝试。'
  // Browser/transport diagnostics belong in diagnostics, never in user notices.
  return /[\u3400-\u9fff]/u.test(message)&&!/[A-Za-z]/.test(message)?message:fallback
}

export function chineseValidationMessage(control){
  const validity=control.validity
  if(validity.valueMissing)return '请填写此项。'
  if(validity.typeMismatch)return control.type==='email'?'请输入有效的邮箱地址。':'请输入有效的地址。'
  if(validity.badInput)return '请输入有效的数值。'
  if(validity.patternMismatch)return '请按要求的格式填写。'
  if(validity.tooShort)return `请至少输入 ${control.minLength} 个字符。`
  if(validity.tooLong)return `请最多输入 ${control.maxLength} 个字符。`
  if(validity.rangeUnderflow)return `请输入不小于 ${control.min} 的数值。`
  if(validity.rangeOverflow)return `请输入不大于 ${control.max} 的数值。`
  if(validity.stepMismatch)return '输入的数值不符合允许的间隔。'
  return '请检查填写内容。'
}

export function installChineseValidation(document){
  document.documentElement.lang='zh-CN'
  const owned=new WeakMap()
  function invalid(event){
    const control=event.target
    if(typeof control?.setCustomValidity!=='function')return
    if(control.validity.customError&&(!owned.has(control)||control.validationMessage!==owned.get(control)))return
    control.setCustomValidity('');const message=chineseValidationMessage(control)
    control.setCustomValidity(message);owned.set(control,message)
  }
  function clear(event){
    if(owned.has(event.target)){
      if(event.target.validationMessage===owned.get(event.target))event.target.setCustomValidity('')
      owned.delete(event.target)
    }
  }
  document.addEventListener('invalid',invalid,true)
  document.addEventListener('input',clear,true);document.addEventListener('change',clear,true)
  return()=>{
    document.removeEventListener('invalid',invalid,true)
    document.removeEventListener('input',clear,true);document.removeEventListener('change',clear,true)
  }
}
