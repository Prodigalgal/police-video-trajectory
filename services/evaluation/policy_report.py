"""Bounded, read-only access to a frozen offline policy comparison."""
import base64
import gzip
import io
import json
from pathlib import Path


MODES={'none','320px_5hz','640px_5hz','320px_5hz_peak','640px_5hz_peak'}


class PolicyReport:
    def __init__(self,path,dataset_id):
        with Path(path).open('rb') as stream:raw=stream.read(512*1024+1)
        if len(raw)>512*1024:raise ValueError('packed comparison exceeds bound')
        packed=base64.b64decode(raw,validate=True)
        with gzip.GzipFile(fileobj=io.BytesIO(packed)) as stream:payload=stream.read(8*1024*1024+1)
        if len(payload)>8*1024*1024:raise ValueError('comparison exceeds bound')
        value=json.loads(payload)
        if (value['schema']!='gate-policy-comparison-v1' or value['baseline_dataset_id']!=dataset_id
                or value['scope']!=[f'recording-0{i}' for i in range(1,5)] or value['region']!='near_field'
                or value['reference_source']!='ai_visual_review' or value['human_independently_verified'] is not False
                or value['automatic_model_promotion'] is not False or value['production_regions_changed'] is not False
                or value['production_processing_resumed'] is not False or value['baseline_parity']['mismatches']!=0):
            raise ValueError('comparison scope or evidence differs')
        self.value=value

    def query(self,model='nanodet',interval=5,mode='none',quality='strict'):
        if model not in {'nanodet','yolox'} or interval not in {1,5} or mode not in MODES or quality not in {'strict','guarded_night'}:
            raise ValueError('invalid comparison filters')
        rows=[v for v in self.value['configurations'] if v['model']==model and v['output_seconds']==interval
              and v['guard_mode']==mode and v['policy']['quality_mode']==quality]
        baseline=next(v for v in self.value['configurations'] if v['model']==model and v['output_seconds']==interval
                      and v['policy_id']=='d5-n3-h10-strict-legacy' and v['guard_mode']=='none')
        return {'scope':self.value['scope'],'region':'near_field','reference_source':'ai_visual_review',
          'temporal_review':self.value['temporal_review'],'baseline_parity':self.value['baseline_parity'],
          'declared_policy_count':self.value['declared_policy_count'],'aggregate_configurations':self.value['aggregate_configurations'],
          'production_event_recall':None,'automatic_model_promotion':False,'baseline':baseline,'rows':rows,
          'guard_mode':mode,'model':model,'output_seconds':interval,'quality_mode':quality,
          'phase_ceiling_for_brief_person':self.value['phase_ceiling_for_brief_person'],
          'limitations':self.value['limitations']}
