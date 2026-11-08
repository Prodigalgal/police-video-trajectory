"""Read-only export of unexpired audit JPEGs with per-sample integrity/provenance."""
import argparse
from contextlib import closing
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import sqlite3
import time


def export(database,output,*,limit=100,cameras=None,now=None):
    database=Path(database).absolute();output=Path(output).absolute()
    scope=set(cameras or [f'recording-0{i}' for i in range(1,5)])
    if (database.is_symlink() or database.resolve()!=database or not database.is_file()
            or type(limit) is not int or not 1<=limit<=512 or not scope or not scope<={f'recording-0{i}' for i in range(1,5)}):
        raise ValueError('invalid private audit export scope')
    if output.is_symlink() or output.resolve()!=output or output.exists():raise ValueError('use a new audit export directory')
    # Freeze a consistent SQLite read snapshot. A live database file digest is
    # not claimed; each exported JPEG and its metadata have their own digest.
    with closing(sqlite3.connect(database.as_uri()+'?mode=ro',uri=True)) as db:
        db.execute('PRAGMA query_only=ON');db.execute('BEGIN')
        if db.execute('SELECT version FROM meta WHERE id=1').fetchone()!=(1,):raise ValueError('unsupported audit schema')
        selected=[];bytes_total=0;clock=time.time() if now is None else now
        for identity,digest,raw,payload in db.execute('SELECT sample_id,payload_sha256,metadata,payload FROM samples WHERE expires_at>? ORDER BY created_at,sample_id',(clock,)):
            metadata=json.loads(raw)
            if metadata.get('camera_id') not in scope:continue
            if (metadata.get('audit_version')!='independent-media-v1' or metadata.get('review_state')!='unreviewed'
                    or metadata.get('human_confirmed') is not False or metadata.get('sent_to_model') is not False
                    or len(payload)>256*1024 or not payload.startswith(b'\xff\xd8\xff')
                    or hashlib.sha256(payload).hexdigest()!=digest or len(identity)!=64
                    or any(c not in '0123456789abcdef' for c in identity)):
                raise ValueError('audit identity, provenance or image integrity differs')
            selected.append((identity,digest,metadata,payload));bytes_total+=len(payload)
            if len(selected)>=limit:break
        if bytes_total>128*1024*1024:raise ValueError('audit export byte bound exceeded')
    output.mkdir(mode=0o700,parents=True,exist_ok=False);rows=[]
    for identity,digest,metadata,payload in selected:
        image=output/(identity+'.jpg');image.write_bytes(payload)
        encoded=json.dumps(metadata,ensure_ascii=False,sort_keys=True,indent=2,allow_nan=False)+'\n'
        (output/(identity+'.json')).write_bytes(encoded.encode('utf-8'))
        rows.append({'sample_id':identity,'image':image.name,'payload_sha256':digest,
            'metadata_sha256':hashlib.sha256(encoded.encode()).hexdigest(),
            'camera_id':metadata['camera_id'],'scene_run_id':metadata.get('scene_run_id'),
            'media_epoch':metadata.get('media_epoch'),'relative_media_ms':metadata.get('relative_media_ms'),
            'scene_state':metadata.get('scene_state'),'review_state':'unreviewed','human_confirmed':False})
    manifest={'export_version':'prefilter-audit-export-v1','exported_at':datetime.now(timezone.utc).isoformat(),
        'read_only_source':True,'original_annotation_writes':0,'new_model_requests':0,'samples':rows,
        'scope':sorted(scope),'image_bytes':bytes_total,'sampled_coverage_is_complete_video':False,
        'training_labels_created':False}
    (output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return manifest


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--database',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--limit',type=int,default=100)
    parser.add_argument('--camera',action='append',choices=[f'recording-0{i}' for i in range(1,5)])
    args=parser.parse_args();result=export(args.database,args.output,limit=args.limit,cameras=args.camera)
    print(json.dumps({'exported_samples':len(result['samples']),'image_bytes':result['image_bytes'],'training_labels_created':False}))


if __name__=='__main__':main()
