"""Rebuild a new index from committed PostgreSQL outbox documents.

No model invocation, index deletion, source-index write, or credential logging.
The caller must pause writers or supply a consistent export plus catch-up.
"""
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import socket
import ssl
import time
import uuid


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def index_name(value):
    if not isinstance(value, str) or not re.fullmatch(r'police-video-observations(?:-[a-z0-9][a-z0-9_-]{0,150})?', value):
        raise ValueError('index name is outside the observation namespace')
    return value


def load_export(path):
    """Validate identifiers, versions and tombstones before any remote write."""
    rows = {}
    with Path(path).open(encoding='utf-8') as source:
        for line in source:
            if len(line.encode()) > 1024*1024:
                raise ValueError('oversized exported observation')
            row = json.loads(line)
            identity = str(uuid.UUID(row['id']))
            version, document = row['version'], row['document']
            if (identity != row['id'] or type(version) is not int or version < 1 or not isinstance(document, dict)
                    or document.get('observation_id') != identity or document.get('record_version') != version
                    or type(document.get('deleted')) is not bool or identity in rows):
                raise ValueError('inconsistent or duplicate exported observation')
            rows[identity] = {'version': version, 'document': document}
    if not rows:
        raise ValueError('empty export requires a separate explicit empty-index procedure')
    return rows


def fingerprint(rows):
    digest = hashlib.sha256()
    for identity in sorted(rows):
        digest.update(canonical({'id': identity, **rows[identity]})+b'\n')
    return digest.hexdigest()


class Elasticsearch:
    """Verify the configured TLS identity even when connecting to a candidate."""
    def __init__(self, host, password, ca, *, server_name='elasticsearch.police-video-trial.svc', username='elastic'):
        if not re.fullmatch(r'[A-Za-z0-9.-]+', host) or not password:
            raise ValueError('invalid maintenance connection')
        self.host, self.server_name = host, server_name
        self.tls = ssl.create_default_context(cafile=ca)
        self.authorization = 'Basic '+base64.b64encode((username+':'+password).encode()).decode()

    def request(self, method, path, body=None, *, ndjson=False, allowed=(200, 201), max_bytes=16*1024*1024):
        if not path.startswith('/') or any(v in path for v in ('\r', '\n', '#')):
            raise ValueError('unsafe Elasticsearch path')
        connection = http.client.HTTPSConnection(self.server_name, 9200, context=self.tls, timeout=90)
        connection._create_connection = lambda address, timeout, source_address=None: socket.create_connection(
            (self.host, 9200), timeout, source_address)
        encoded = body if isinstance(body, bytes) else None if body is None else canonical(body)
        try:
            connection.request(method, path, encoded, headers={'Authorization':self.authorization,
                'Content-Type':'application/x-ndjson' if ndjson else 'application/json'})
            response = connection.getresponse()
            raw = response.read(max_bytes+1)
            if response.status not in allowed or len(raw)>max_bytes:
                raise RuntimeError(f'Elasticsearch maintenance request rejected: {method} HTTP {response.status}')
            return json.loads(raw) if raw else {}
        finally:
            connection.close()

    def documents(self, index):
        index_name(index)
        rows, scroll = {}, None
        try:
            result = self.request('POST','/'+index+'/_search?scroll=2m',
                                  {'size':250,'sort':['_doc'],'version':True,'query':{'match_all':{}}})
            while True:
                scroll = result.get('_scroll_id', scroll)
                if result.get('timed_out') or result.get('_shards',{}).get('failed',0):
                    raise RuntimeError('incomplete Elasticsearch verification scan')
                hits = result['hits']['hits']
                if not hits:
                    break
                for hit in hits:
                    if hit['_id'] in rows:
                        raise RuntimeError('duplicate document in verification scan')
                    rows[hit['_id']] = {'version':hit['_version'],'document':hit['_source']}
                if not scroll:
                    raise RuntimeError('verification scan has no cursor')
                result = self.request('POST','/_search/scroll',{'scroll':'2m','scroll_id':scroll})
        finally:
            if scroll:
                self.request('DELETE','/_search/scroll',{'scroll_id':[scroll]})
        return rows

    def verify(self, index, expected):
        actual = self.documents(index)
        if actual != expected or fingerprint(actual) != fingerprint(expected):
            raise RuntimeError('Elasticsearch document/version/tombstone digest differs from committed export')
        return {'documents':len(actual),'sha256':fingerprint(actual),
                'tombstones':sum(row['document']['deleted'] for row in actual.values())}

    def rebuild_new(self, index, mapping, rows):
        index_name(index)
        # PUT fails if the index exists; never replace an existing corpus.
        definition = {key:value for key,value in mapping.items() if key!='aliases'}
        self.request('PUT','/'+index,definition)
        records = sorted(rows.items())
        for offset in range(0,len(records),250):
            batch = records[offset:offset+250]
            payload = b''.join(canonical({'index':{'_index':index,'_id':identity,
                'version':row['version'],'version_type':'external'}})+b'\n'+canonical(row['document'])+b'\n'
                for identity,row in batch)
            response = self.request('POST','/_bulk',payload,ndjson=True)
            items = response.get('items',[])
            if response.get('errors') or len(items)!=len(batch) or any(
                    item.get('index',{}).get('status') not in (200,201) for item in items):
                raise RuntimeError('rebuild bulk failed; partial candidate retained for investigation')
        self.request('POST','/'+index+'/_refresh')
        return self.verify(index,rows)

    def snapshot_and_restore(self, index, rows, suffix):
        """Use the supported snapshot API, then verify an isolated restored copy."""
        index_name(index)
        if not re.fullmatch(r'[0-9]{8}t[0-9]{6}z',suffix):
            raise ValueError('invalid immutable backup name')
        repository, snapshot = 'police-video-backups', 'observations-'+suffix
        self.request('PUT','/_snapshot/'+repository,{'type':'fs','settings':{'location':'es','compress':True}})
        verified = self.request('POST','/_snapshot/'+repository+'/_verify')
        if not verified.get('nodes'):
            raise RuntimeError('snapshot repository is not verified')
        saved = self.request('PUT','/_snapshot/'+repository+'/'+snapshot+'?wait_for_completion=true',
            {'indices':index,'include_global_state':False,'feature_states':['none']})
        state = saved.get('snapshot',{})
        if state.get('state')!='SUCCESS' or state.get('shards',{}).get('failed')!=0:
            raise RuntimeError('snapshot did not complete successfully')
        restored = index_name('police-video-observations-restore-'+suffix)
        self.request('POST','/_snapshot/'+repository+'/'+snapshot+'/_restore?wait_for_completion=true',
            {'indices':index,'rename_pattern':re.escape(index),'rename_replacement':restored,
             'include_global_state':False,'include_aliases':False,'feature_states':['none']})
        self.request('GET','/_cluster/health/'+restored+'?wait_for_status=yellow&timeout=60s')
        self.request('POST','/'+restored+'/_refresh')
        checked = self.verify(restored,rows)
        return {'repository':repository,'snapshot':snapshot,'state':'SUCCESS',
                'restored_index':restored,'restored_verification':checked,
                'storage_boundary':'same-node retained volume; off-node disaster recovery remains separate'}

    def protect_daily(self,index):
        index_name(index)
        policy_name = 'police-video-observations-daily'
        policy = {'name':'<police-video-daily-{now/d}>','schedule':'0 0 1 * * ?',
                  'repository':'police-video-backups',
                  'config':{'indices':[index],'include_global_state':False,'feature_states':['none']},
                  'retention':{'expire_after':'14d','min_count':2,'max_count':15}}
        self.request('PUT','/_slm/policy/'+policy_name,policy)
        current = self.request('GET','/_slm/policy/'+policy_name)[policy_name]
        if current.get('policy')!=policy:
            raise RuntimeError('stored SLM policy differs from declaration')
        executed = self.request('POST','/_slm/policy/'+policy_name+'/_execute')
        snapshot = executed.get('snapshot_name','')
        if not re.fullmatch(r'police-video-daily-[a-z0-9._-]{1,180}',snapshot):
            raise RuntimeError('SLM execution did not provide a safe snapshot identifier')
        deadline = time.monotonic()+150
        while time.monotonic()<deadline:
            result = self.request('GET','/_snapshot/police-video-backups/'+snapshot)['snapshots']
            state = result[0] if len(result)==1 else {}
            if state.get('state')=='SUCCESS':
                if state.get('indices')!=[index] or state.get('shards',{}).get('failed')!=0:
                    raise RuntimeError('SLM snapshot scope/shards differ')
                return {'policy':policy_name,'schedule_utc':'01:00 daily','retention':policy['retention'],
                        'executed_snapshot':snapshot,'state':'SUCCESS','off_node_copy':False}
            if state.get('state') in {'FAILED','PARTIAL','INCOMPATIBLE'}:
                raise RuntimeError('first SLM snapshot failed')
            time.sleep(2)
        raise RuntimeError('SLM snapshot completion was not observed')


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--phase',choices=['inventory','migrate','verify','snapshot','schedule'],required=True)
    parser.add_argument('--index',default='police-video-observations-v1')
    parser.add_argument('--export',default='/backups/observations.jsonl')
    parser.add_argument('--mapping',default='/maintenance/mapping.json')
    parser.add_argument('--stamp',required=True)
    args = parser.parse_args()
    source = Elasticsearch('elasticsearch.police-video-trial.svc',os.environ['ES_ADMIN_PASSWORD'],'/es-ca/ca.crt')
    candidate = Elasticsearch('elasticsearch-durable.police-video-trial.svc',os.environ['ES_ADMIN_PASSWORD'],'/es-ca/ca.crt')
    if args.phase=='inventory':
        print(json.dumps(source.request('GET','/_cat/indices?format=json&h=index,docs.count,health,uuid')),flush=True)
        return
    rows = load_export(args.export)
    report = {'phase':args.phase,'result':'running','source_export_sha256':fingerprint(rows),
              'documents':len(rows),'index':index_name(args.index),'model_requests':0}
    if args.phase=='migrate':
        report['old_index_verification'] = source.verify(args.index,rows)
        # Re-create only the application's scoped principals. Passwords remain
        # K8s Secret references; never export the old security index filesystem.
        for role in ('police_video_reader','police_video_indexer'):
            value = source.request('GET','/_security/role/'+role)
            definition = value[role]
            definition.pop('transient_metadata',None)
            candidate.request('PUT','/_security/role/'+role,definition)
        for username,key,role in [(os.environ['ES_READER_USER'],'ES_READER_PASSWORD','police_video_reader'),
                                  (os.environ['ES_INDEXER_USER'],'ES_INDEXER_PASSWORD','police_video_indexer')]:
            if not re.fullmatch(r'[a-zA-Z0-9_-]{1,100}',username):
                raise ValueError('invalid scoped principal')
            candidate.request('PUT','/_security/user/'+username,
                              {'password':os.environ[key],'roles':[role],'enabled':True})
        report['rebuilt_verification'] = candidate.rebuild_new(args.index,json.loads(Path(args.mapping).read_text()),rows)
        candidate.request('POST','/_aliases',{'actions':[{'add':{'index':args.index,'alias':'police-video-observations'}}]})
        report['snapshot_drill'] = candidate.snapshot_and_restore(args.index,rows,args.stamp)
    elif args.phase=='verify':
        report['verification'] = candidate.verify(args.index,rows)
        for username,key in [(os.environ['ES_READER_USER'],'ES_READER_PASSWORD'),(os.environ['ES_INDEXER_USER'],'ES_INDEXER_PASSWORD')]:
            scoped = Elasticsearch(candidate.host,os.environ[key],'/es-ca/ca.crt',username=username)
            identity = scoped.request('GET','/_security/_authenticate')
            if identity.get('username')!=username:
                raise RuntimeError('application principal did not survive restart')
        report['scoped_credentials'] = 'verified'
    elif args.phase=='schedule':
        report['daily_protection'] = candidate.protect_daily(args.index)
    else:
        report['snapshot_drill'] = candidate.snapshot_and_restore(args.index,rows,args.stamp)
    report['result'] = 'passed'
    print(json.dumps(report),flush=True)


if __name__=='__main__':
    main()
