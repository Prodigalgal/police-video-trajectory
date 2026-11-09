export function liveSource(camera,quality){
  if(!/^[A-Za-z0-9_-]{1,100}$/.test(camera)||!['preview','hls'].includes(quality))throw new Error('监控参数无效。')
  return `/api/v1/live/cameras/${encodeURIComponent(camera)}/${quality}/index.m3u8`
}
export function liveConfiguration(quality){
  return {enableWorker:false,lowLatencyMode:false,maxBufferLength:quality==='preview'?6:15,backBufferLength:0,
    maxMaxBufferLength:24,liveSyncDurationCount:2,liveMaxLatencyDurationCount:6,maxLiveSyncPlaybackRate:1.1,
    manifestLoadingTimeOut:20000,levelLoadingTimeOut:20000,fragLoadingTimeOut:quality==='preview'?15000:45000}
}
export function bufferedPlaybackTarget(ranges,current,preferred){
  const valid=ranges.filter(([start,end])=>Number.isFinite(start)&&Number.isFinite(end)&&end>start)
  if(!valid.length||!Number.isFinite(current)||valid.some(([start,end])=>current>=start&&current<=end+.1))return null
  const [start,end]=valid.at(-1),margin=Math.min(.1,(end-start)/3)
  return Math.max(start+margin,Math.min(end-margin,Number.isFinite(preferred)?preferred:end-.5))
}
