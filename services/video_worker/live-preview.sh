#!/bin/sh
# MediaMTX owns this process: a reader starts it and idle readers release it.
set -eu
case "${MTX_PATH:-}" in
  preview-recording-0[1-4]) stream="${MTX_PATH#preview-}" ;;
  *) echo 'live_preview_invalid_path' >&2; exit 64 ;;
esac
host="${PREVIEW_RTSP_HOST:?preview host required}"
port="${PREVIEW_RTSP_PORT:?preview port required}"
user="${PREVIEW_RTSP_USER:?preview reader required}"
password="${MTX_AUTHINTERNALUSERS_1_PASS:?preview credential required}"
case "$password$user" in
  *[!A-Za-z0-9_.~-]*) echo 'live_preview_invalid_credential_encoding' >&2; exit 64 ;;
esac
locked=false
for slot in 1 2; do
  exec 9>"/tmp/live-preview-slot-$slot.lock"
  if flock -n 9; then locked=true; break; fi
  exec 9>&-
done
if [ "$locked" != true ]; then echo 'live_preview_capacity_wait' >&2; exit 75; fi
directory=$(mktemp -d /tmp/live-preview.XXXXXX)
pid='';logger=''
cleanup() {
  trap - EXIT INT TERM
  if [ -n "$pid" ]; then kill -TERM "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  if [ -n "$logger" ]; then kill -TERM "$logger" 2>/dev/null || true; wait "$logger" 2>/dev/null || true; fi
  rm -f "$directory/stderr"
  rmdir "$directory"
}
trap cleanup EXIT
trap 'exit 0' INT TERM
mkfifo "$directory/stderr"
(
  count=0
  while IFS= read -r notice; do
    count=$((count+1))
    if [ "$count" -le 3 ]; then
      case "$notice" in
        *401*|*403*) category=authorization ;;
        *timed*out*|*timeout*) category=timeout ;;
        *) category=decoder_or_transport ;;
      esac
      printf 'live_preview_notice camera=%s category=%s\n' "$stream" "$category" >&2
    fi
  done < "$directory/stderr"
) &
logger=$!
printf 'live_preview_started camera=%s slot=%s\n' "$stream" "$slot" >&2
ffmpeg -nostdin -hide_banner -loglevel error -threads 1 -filter_threads 1 \
  -tls_verify 1 -ca_file /secrets/ca.crt -rtsp_transport tcp -timeout 15000000 \
  -i "rtsps://$user:$password@$host:$port/$stream" -map 0:v:0 -an \
  -vf 'fps=10,scale=640:-2:flags=fast_bilinear' -c:v libx264 -threads 1 \
  -preset veryfast -tune zerolatency -pix_fmt yuv420p -profile:v baseline \
  -b:v 480k -maxrate 600k -bufsize 960k -g 10 -keyint_min 10 -sc_threshold 0 \
  -rtsp_transport tcp -f rtsp "rtsps://127.0.0.1:$port/$MTX_PATH" 2> "$directory/stderr" &
pid=$!
set +e
wait "$pid"
status=$?
set -e
pid=''
printf 'live_preview_stopped camera=%s status=%s\n' "$stream" "$status" >&2
exit "$status"
