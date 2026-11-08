import {businessApi} from '../investigation-client.js'
import {createMapProvider} from './map-providers.js'
import {validMapView} from './map-document.js'
import './amap-adapter.js'
import './map-frame.css'

const root=document.getElementById('map-root'),abort=new AbortController()
let nonce=null,latest=null,instance=null,initializing=false,disposed=false
function send(kind,extra={}){if(nonce&&!disposed)window.parent.postMessage({schema:'map-document-v1',nonce,kind,...extra},location.origin)}
function fail(code='resource'){send('error',{code})}
async function initialize(){
  if(initializing||disposed)return
  initializing=true
  try{
    const config=await businessApi('/api/v1/maps/config',{signal:abort.signal})
    if(!config.configured){fail('configuration');return}
    const created=await createMapProvider(config.provider,root,config,{
      onSelect:point=>send('select',{id:point.observation_id||point.camera_id}),
      onPick:point=>{if(latest?.pick)send('pick',point)}
    })
    if(disposed){created.destroy();return}
    instance=created;render()
  }catch{if(!disposed)fail()}
}
function render(){
  if(!instance||!latest)return
  instance.render(latest.points.map(point=>({...point,observation_id:point.id})),latest.links);send('loaded')
}
function receive(event){
  if(event.source!==window.parent||event.origin!==location.origin||window.parent===window||event.data?.schema!=='map-document-v1')return
  const value=event.data
  if(value.kind==='hello'&&typeof value.nonce==='string'&&/^[a-f0-9-]{36}$/.test(value.nonce)){
    if(nonce&&nonce!==value.nonce)return
    nonce=value.nonce;send('ready');return
  }
  if(!nonce||value.nonce!==nonce||value.kind!=='view'||!validMapView(value))return
  latest=value
  if(instance)render();else initialize()
}
function close(){disposed=true;abort.abort();window.removeEventListener('message',receive);window.removeEventListener('error',error);window.removeEventListener('unhandledrejection',error);instance?.destroy();instance=null}
function error(){if(!disposed)fail()}
window.addEventListener('message',receive);window.addEventListener('error',error);window.addEventListener('unhandledrejection',error);window.addEventListener('pagehide',close,{once:true})
