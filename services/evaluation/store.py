"""Immutable dataset registry and revision-fenced SQLite annotation/audit ledger."""
from contextlib import closing
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3


class Conflict(Exception):pass


class Dataset:
    def __init__(self,root,recordings=None):
        self.root=Path(root).resolve();self.recordings=Path(recordings).resolve() if recordings else None
        summary=json.loads((self.root/'summary.json').read_text())
        if summary['scope']!=[f'recording-0{i}' for i in range(1,5)]:raise ValueError('only four-point dataset permitted')
        self.frames={};self.models=[];self.source_hashes={};self.latencies={};self.video_verified={}
        for number in range(1,5):
            camera=f'recording-0{number}';point=json.loads((self.root/(camera+'.json')).read_text())
            self.source_hashes[camera]=point['source_sha256'];anchors={v['sample_id']:v for v in point['anchors']}
            for sample in point['samples']:
                if 'review_still' not in sample:continue
                identity=sample['sample_id'];anchor=anchors[identity];path=self.root/'stills'/sample['review_still']
                if (not re.fullmatch(r'recording-0[1-4]-\d{6}',identity) or not identity.startswith(camera+'-') or
                    sample['review_still']!=identity+'.jpg' or path.resolve().parent!=self.root/'stills' or path.is_symlink()):raise ValueError('unsafe review frame')
                digest=hashlib.sha256(path.read_bytes()).hexdigest()
                if digest!=sample['review_still_sha256'] or digest!=anchor['jpeg_sha256']:raise ValueError('review image and prediction input differ')
                self.frames[identity]={'id':identity,'camera':camera,'offset_ms':sample['offset_ms'],'sha256':digest,
                  'width':anchor['image_width'],'height':anchor['image_height'],'path':path,'state':sample['state'],
                  'quality':sample['quality'],'cases':anchor['cases']}
            for model,stats in point['summary']['detector_latency'].items():
                self.latencies.setdefault(model,[]).append(stats)
        if not 1<=len(self.frames)<=500:raise ValueError('unexpected review dataset size')
        self.models=sorted(next(iter(self.frames.values()))['cases'])
        self.latencies={k:{'mean_camera_p50_ms':round(sum(v['p50_ms'] for v in rows)/len(rows),3),
                           'worst_camera_p95_ms':max(v['p95_ms'] for v in rows),'scope':'four-camera single-thread ARM64 comparison'} for k,rows in self.latencies.items()}
        self.identity=hashlib.sha256(json.dumps({key:v['sha256'] for key,v in sorted(self.frames.items())},sort_keys=True).encode()).hexdigest()

    def frame(self,identity):
        if identity not in self.frames:raise KeyError('unknown evaluation frame')
        return self.frames[identity]

    def image(self,identity):
        frame=self.frame(identity);path=frame['path']
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest()!=frame['sha256']:raise ValueError('evaluation image changed')
        return path

    def video(self,camera):
        if camera not in self.source_hashes or self.recordings is None:raise KeyError('unknown recording')
        path=self.recordings/(camera+'.mp4')
        if path.is_symlink() or not path.is_file():raise KeyError('recording unavailable')
        signature=(path.stat().st_size,path.stat().st_mtime_ns)
        if self.video_verified.get(camera)!=signature:
            digest=hashlib.sha256()
            with path.open('rb') as source:
                while data:=source.read(1048576):digest.update(data)
            if digest.hexdigest()!=self.source_hashes[camera]:raise ValueError('recording source changed')
            self.video_verified[camera]=signature
        return path


class Labels:
    def __init__(self,path,dataset):
        self.path=Path(path);self.dataset=dataset;self.path.parent.mkdir(parents=True,exist_ok=True)
        if self.path.is_symlink():raise ValueError('unsafe annotation ledger')
        with closing(self.connect()) as db:
            db.executescript('''CREATE TABLE IF NOT EXISTS annotations(dataset TEXT NOT NULL,frame TEXT NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(dataset,frame));
             CREATE TABLE IF NOT EXISTS annotation_audit(event_id INTEGER PRIMARY KEY AUTOINCREMENT,dataset TEXT NOT NULL,frame TEXT NOT NULL,revision INTEGER NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,occurred_at TEXT NOT NULL,before_json TEXT,after_json TEXT NOT NULL);''')

    def connect(self):
        db=sqlite3.connect(self.path,timeout=10,isolation_level=None)
        db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA synchronous=FULL');db.execute('PRAGMA busy_timeout=10000')
        db.execute('PRAGMA max_page_count=65536')
        return db

    def empty(self,identity):
        frame=self.dataset.frame(identity)
        return {'frame_id':identity,'revision':0,'status':'unreviewed','image_sha256':frame['sha256'],
          'annotation_source':'human','review_source':None,
          'scene':'unknown','presence_person':None,'presence_vehicle':None,'objects':[],'boxes_complete':False,
          'conditions':[],'notes':'','actor':'','reviewed_at':None}

    def all(self):
        with closing(self.connect()) as db:
            return {key:json.loads(payload) for key,payload in db.execute('SELECT frame,payload FROM annotations WHERE dataset=?',(self.dataset.identity,))}

    def get(self,identity):return self.all().get(identity,self.empty(identity))

    def write(self,identity,revision,actor,value=None,*,verify=False,ai_review=False):
        if verify and ai_review:raise ValueError('review sources are mutually exclusive')
        frame=self.dataset.frame(identity);self.dataset.image(identity)
        with closing(self.connect()) as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT revision,payload FROM annotations WHERE dataset=? AND frame=?',(self.dataset.identity,identity)).fetchone()
            before=json.loads(row[1]) if row else self.empty(identity)
            if (row[0] if row else 0)!=revision:raise Conflict('annotation revision changed')
            if (verify or ai_review) and row is None:raise Conflict('save a draft before review')
            if ai_review and (before.get('annotation_source')!='ai_visual_review' or before['status']=='verified'):
                raise ValueError('AI review cannot replace a human-confirmed annotation')
            if db.execute('SELECT count(*) FROM annotation_audit').fetchone()[0]>=50000:raise ValueError('annotation history limit reached')
            payload={**(before if verify or ai_review else value),'frame_id':identity,'revision':revision+1,
                'image_sha256':frame['sha256'],'status':'verified' if verify else 'ai_reviewed' if ai_review else 'draft','actor':actor,
                'review_source':'human' if verify else 'ai_visual_review' if ai_review else None,
                'reviewed_at':datetime.now(timezone.utc).isoformat() if verify or ai_review else None}
            encoded=json.dumps(payload,ensure_ascii=False,sort_keys=True,allow_nan=False)
            db.execute('INSERT INTO annotations VALUES(?,?,?,?) ON CONFLICT(dataset,frame) DO UPDATE SET revision=excluded.revision,payload=excluded.payload',
                       (self.dataset.identity,identity,revision+1,encoded))
            db.execute('INSERT INTO annotation_audit(dataset,frame,revision,actor,action,occurred_at,before_json,after_json) VALUES(?,?,?,?,?,?,?,?)',
                (self.dataset.identity,identity,revision+1,actor,'verify' if verify else 'ai_review' if ai_review else 'save_draft',datetime.now(timezone.utc).isoformat(),json.dumps(before),encoded))
            db.execute('COMMIT');return payload
