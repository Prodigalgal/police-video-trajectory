export function videoOffsetMs(item) {
  const video = item?.video
  if (video?.kind === 'session_segment') {
    return video.available === true && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(video.recording_id || '') && Number.isSafeInteger(video.seek_offset_ms) && video.seek_offset_ms >= 0 && video.seek_offset_ms <= 20000 ? video.seek_offset_ms : null
  }
  return Number.isFinite(item?.media_offset_ms) && item.media_offset_ms >= 0 ? item.media_offset_ms : null
}
