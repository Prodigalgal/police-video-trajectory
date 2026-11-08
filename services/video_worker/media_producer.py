"""Isolated encoding subprocess. Only local media paths and an immutable job enter it."""
import argparse
import json
import sys
from .media_clips import ClipStore,MediaError,produce
from . import session_media

PROFILE='archive-h264-10fps-v1'


def validate_job(job):
    import re
    from .media_clips import validate_request,canonical
    import hashlib
    if not isinstance(job,dict):raise ValueError('invalid media job')
    kind=job.get('inputKind','archive')
    if kind not in {'archive','session'}:raise ValueError('invalid media input kind')
    if kind=='archive':
        if job.get('profileVersion')!=PROFILE or job.get('sessionInput') is not None:raise ValueError('invalid media profile')
        validate_request(job.get('sourceId'),job.get('cameraId'),job.get('startMs'),job.get('endMs'),job.get('jobId'))
    else:
        import uuid
        value=session_media.input_value(job.get('sessionInput'))
        if not isinstance(job.get('jobId'),str) or str(uuid.UUID(job['jobId']))!=job['jobId']:raise ValueError('invalid media identity')
        expected={'sourceId':value['source_id'],'cameraId':value['camera_id'],'startMs':0,'endMs':value['end_ms'],'profileVersion':session_media.PROFILE,
            'sourceFingerprint':value['source_fingerprint'],'sourceBytes':value['source']['bytes'],'sourceSha256':value['source']['sha256']}
        if any(job.get(key)!=entry or type(job.get(key))!=type(entry) for key,entry in expected.items()):raise ValueError('media session input mismatch')
    if type(job.get('generation')) is not int or job['generation']<1 or type(job.get('sourceBytes')) is not int or not 0<job['sourceBytes']<=8*1024**3:raise ValueError('invalid media job metadata')
    for key in ('inputSha256','sourceFingerprint','sourceSha256'):
        if not isinstance(job.get(key),str) or not re.fullmatch('[a-f0-9]{64}',job[key]):raise ValueError('invalid media job digest')
    if kind=='archive':
        value={'source_id':job['sourceId'],'camera_id':job['cameraId'],'start_ms':job['startMs'],'end_ms':job['endMs'],
            'profile_version':PROFILE,'source_fingerprint':job['sourceFingerprint'],'source':{'bytes':job['sourceBytes'],'sha256':job['sourceSha256']}}
    if hashlib.sha256(canonical(value)).hexdigest()!=job['inputSha256']:raise ValueError('media input digest mismatch')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--recordings',required=True);parser.add_argument('--store',required=True)
    parser.add_argument('--live-recordings')
    parser.add_argument('--bytes',type=int,required=True);parser.add_argument('--entries',type=int,required=True);args=parser.parse_args()
    try:
        raw=sys.stdin.buffer.read(65537)
        if len(raw)>65536:raise ValueError('media job exceeded bound')
        job=json.loads(raw);validate_job(job)
        store=ClipStore(args.store,limit=args.bytes,entries=args.entries)
        if job.get('inputKind','archive')=='session':
            if not args.live_recordings:raise ValueError('missing native recording root')
            result=session_media.produce_session(store,args.live_recordings,job['sessionInput'],job['jobId'])
        else:
            result=produce(store,args.recordings,job['sourceId'],job['cameraId'],job['startMs'],job['endMs'],job['jobId'],
                expected_source={'bytes':job['sourceBytes'],'sha256':job['sourceSha256']})
        print(json.dumps({'clipId':result['clip_id'],'manifestSha256':result['manifest_sha256'],'inputSha256':job['inputSha256']}),flush=True)
    except (OSError,ValueError,RuntimeError) as error:
        allowed={'media_source_changed','media_source_unavailable','media_capacity_reached','media_store_busy','media_timeout'}
        invalid={'media_clock_unavailable','media_clock_discontinuity','media_corrupt_frame','media_window_incomplete','media_invalid_dimensions','media_output_clock_conflict','media_invalid_window','media_session_changed','media_conflicting_replay'}
        code='media_input_invalid' if isinstance(error,MediaError) and str(error) in invalid else str(error) if isinstance(error,MediaError) and str(error) in allowed else 'media_source_unavailable' if isinstance(error,OSError) else 'media_worker_error'
        print(json.dumps({'error_code':code}),flush=True);raise SystemExit(1)


if __name__=='__main__':main()
