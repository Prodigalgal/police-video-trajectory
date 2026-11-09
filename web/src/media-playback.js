export function releaseDetachedVideo(video){
  if(!video||video.isConnected)return
  video.pause()
  video.removeAttribute('src')
  video.load()
}
