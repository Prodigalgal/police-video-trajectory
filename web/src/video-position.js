export function videoOffsetMs(item) {
  return Number.isFinite(item?.media_offset_ms) && item.media_offset_ms >= 0 ? item.media_offset_ms : null
}
