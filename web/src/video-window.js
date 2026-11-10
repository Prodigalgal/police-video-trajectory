import {videoOffsetMs} from './video-position.js'

export const contextSeconds=[3,5,10]

export function videoWindow(item,duration,seconds=5,mode='window'){
  if(!contextSeconds.includes(seconds)||!['window','full'].includes(mode))throw new Error('回看范围无效。')
  if(!Number.isFinite(duration)||duration<=0||!Number.isSafeInteger(Math.round(duration*1000)))return null
  const offset=videoOffsetMs(item),anchor=offset===null?null:offset/1000
  const valid=anchor!==null&&anchor<duration
  if(mode==='full'||!valid)return {start:0,end:duration,anchor:valid?anchor:null,mode:'full',reason:valid?null:anchor===null?'position_unavailable':'position_outside_recording'}
  return {start:Math.max(0,anchor-seconds),end:Math.min(duration,anchor+seconds),anchor,mode:'window',reason:null}
}

export function boundedVideoTime(value,window){
  if(!window||!Number.isFinite(value))throw new Error('录像位置无效。')
  return Math.max(window.start,Math.min(window.end,value))
}

export function contextCoverage(window,seconds){
  if(!window||window.mode!=='window'||!contextSeconds.includes(seconds)||!Number.isFinite(window.anchor))return null
  const before=window.anchor-window.start,after=window.end-window.anchor
  if(before<0||after<0||!Number.isFinite(before)||!Number.isFinite(after))return null
  return {before,after,requested:seconds,complete:before+.001>=seconds&&after+.001>=seconds}
}
