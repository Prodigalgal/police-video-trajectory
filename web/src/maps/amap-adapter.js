import {registerMapProvider} from './map-providers.js'
let loading=null,loadedKey=null
function load(configuration){
  if(!configuration.configured||!configuration.js_api_key)throw new Error('地图服务尚未配置，请联系管理员。')
  if(loadedKey&&loadedKey!==configuration.js_api_key)throw new Error('地图配置已变化，请刷新页面。')
  if(window.AMap&&loadedKey===configuration.js_api_key)return Promise.resolve(window.AMap)
  if(loading)return loading
  window._AMapSecurityConfig={serviceHost:location.origin+'/_AMapService'}
  const key=configuration.js_api_key
  loading=new Promise((resolve,reject)=>{
    const script=document.createElement('script'),callback='__videoAmapReady'+crypto.randomUUID().replaceAll('-','')
    let settled=false
    function finish(error){if(settled)return;settled=true;clearTimeout(timer);delete window[callback];script.onerror=null;if(error){script.remove();loading=null;reject(error)}else{loadedKey=key;resolve(window.AMap)}}
    const timer=setTimeout(()=>finish(new Error('地图加载超时，可重新加载。')),15000)
    window[callback]=()=>window.AMap?finish():finish(new Error('地图服务未正确初始化。'))
    script.async=true;script.onerror=()=>finish(new Error('地图资源暂不可用，可重新加载。'))
    script.src='https://webapi.amap.com/maps?'+new URLSearchParams({v:'2.0',key,callback})
    document.head.appendChild(script)
  })
  return loading
}
registerMapProvider('amap',async(element,configuration,{onSelect,onPick}={})=>{
  const AMap=await load(configuration)
  if(!element?.isConnected)throw new Error('地图视图已关闭。')
  const map=new AMap.Map(element,{zoom:4,center:[105,35],viewMode:'2D',resizeEnable:true,lang:'zh_cn'})
  const pick=event=>onPick?.({longitude:Number(event.lnglat.lng.toFixed(6)),latitude:Number(event.lnglat.lat.toFixed(6))})
  if(onPick)map.on('click',pick)
  return {
    render(points,links=[]){
      map.clearMap();const overlays=[],positions=new Map()
      for(const point of points){
        if(!Number.isFinite(point.longitude)||!Number.isFinite(point.latitude)||Math.abs(point.longitude)>180||Math.abs(point.latitude)>90)continue
        const position=[point.longitude,point.latitude],title=String(point.camera_name||point.display_name||point.camera_id)
        const content=document.createElement('button')
        content.type='button';content.className='map-point-marker';content.textContent=point.camera_id.match(/^recording-(\d+)$/)?.[1]||String(positions.size+1)
        content.setAttribute('aria-label','查看'+title);content.title=title
        content.addEventListener('click',event=>{event.stopPropagation();onSelect?.(point)})
        const marker=new AMap.Marker({position,title,content,anchor:'bottom-center'})
        overlays.push(marker);positions.set(point.observation_id||point.camera_id,position)
      }
      for(const link of links){
        const start=positions.get(link.from_observation_id),end=positions.get(link.to_observation_id)
        if(!start||!end||link.review_state==='rejected'||link.review_stale)continue
        overlays.push(new AMap.Polyline({path:[start,end],strokeColor:link.review_state==='accepted'?'#39755b':'#4b78ab',strokeWeight:3,strokeStyle:'dashed',showDir:true}))
      }
      map.add(overlays)
      if(overlays.length)map.setFitView(overlays,false,[40,40,40,40],16)
    },
    destroy(){if(onPick)map.off('click',pick);map.destroy()}
  }
})
