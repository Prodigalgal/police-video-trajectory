#!/usr/bin/env bash
# Logical PostgreSQL backup; an optional isolated restore checks real records.
set -euo pipefail
umask 077
mode=${1:?backup or drill}
stamp=${2:-$(date -u +%Y%m%dt%H%M%Sz)}
[[ "$mode" == backup || "$mode" == drill ]]
[[ "$stamp" =~ ^[0-9]{8}t[0-9]{6}z$ ]]
[[ "${PGSSLMODE:?}" == verify-full ]]
[[ -f "${PGSSLROOTCERT:?}" ]]
[[ -n "${PGPASSWORD:?}" && -n "${PGHOST:?}" && -n "${PGUSER:?}" ]]
[[ "${PGDATABASE:?}" == video_trajectory ]]
test ! -L /backups/postgresql
mkdir -p /backups/postgresql
root=/backups/postgresql
backup="$root/video-trajectory-$stamp.dump"
test ! -e "$backup"
test ! -e "$backup.partial"
available=$(df -Pk "$root" | awk 'NR==2 {print $4}')
[[ "$available" =~ ^[0-9]+$ && "$available" -gt 2097152 ]]

manifest_sql="SELECT jsonb_build_object(
 'observations',(SELECT jsonb_build_object('rows',count(*),'digest',md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY observation_id),''))) FROM public.observations t),
 'index_outbox',(SELECT jsonb_build_object('rows',count(*),'digest',md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY event_id),''))) FROM public.index_outbox t),
 'frames',(SELECT jsonb_build_object('rows',count(*),'digest',md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY frame_id),''))) FROM public.frames t),
 'captures',(SELECT jsonb_build_object('rows',count(*),'digest',md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY frame_id),''))) FROM public.capture_outbox t),
 'targets',(SELECT jsonb_build_object('rows',count(*),'digest',md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY target_id),''))) FROM public.detected_targets t),
 'jobs',(SELECT jsonb_build_object('rows',count(*),'digest',md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY job_id),''))) FROM public.analysis_jobs t),
 'sources',(SELECT jsonb_build_object('rows',count(*),'digest',md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY source_id),''))) FROM public.sources t),
 'tasks',(SELECT jsonb_build_object('rows',count(*),'digest',md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY task_id),''))) FROM public.processing_tasks t))"
if [[ "$mode" == drill ]]; then
    before=$(psql -X -v ON_ERROR_STOP=1 -Atc "$manifest_sql")
fi
pg_dump --format=custom --compress=6 --no-owner --no-acl --lock-wait-timeout=60s --file="$backup.partial"
bytes=$(stat -c %s "$backup.partial")
[[ "$bytes" -gt 0 && "$bytes" -lt 4294967296 ]]
sha=$(sha256sum "$backup.partial" | awk '{print $1}')
[[ "$sha" =~ ^[0-9a-f]{64}$ ]]
mv -T -- "$backup.partial" "$backup"
printf '{"result":"passed","kind":"postgresql-logical-backup","sha256":"%s","bytes":%s,"file":"%s","off_node_copy":false}\n' \
    "$sha" "$bytes" "$backup" > "$backup.manifest.json"

if [[ "$mode" == drill ]]; then
    restored="recovery_${stamp}"
    createdb "$restored"
    pg_restore --exit-on-error --no-owner --no-acl --dbname="$restored" "$backup" >/dev/null
    actual=$(psql -X -v ON_ERROR_STOP=1 --dbname="$restored" -Atc "$manifest_sql")
    after=$(psql -X -v ON_ERROR_STOP=1 -Atc "$manifest_sql")
    # The operator's paused-writer gate is additionally checked before/after.
    # Ordinary scheduled backups use pg_dump's consistent snapshot without
    # claiming current-live row equality while application writes continue.
    [[ "$before" == "$actual" && "$before" == "$after" ]]
    printf '%s\n' "$actual" > "$backup.restore-business-digests.json"
    printf '{"result":"passed","kind":"postgresql-restore-drill","sha256":"%s","bytes":%s,"file":"%s","restored_database":"%s","business_tables_verified":8,"off_node_copy":false}\n' \
        "$sha" "$bytes" "$backup" "$restored"
else
    cat "$backup.manifest.json"
fi
