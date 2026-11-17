"""Self-contained model-interface contracts; no image-retrieval accuracy claims."""
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import httpx
import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from services.appearance.catalog import Catalog,Definition,Snapshot,canonical
from services.appearance.encoder import Encoder,pixels
from services.web_gateway.app import create_app
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN


def general(payload=b'interface metadata only'):
    return Definition(profile='general',model_file='general.onnx',model_sha256=hashlib.sha256(payload).hexdigest(),dimensions=384,normalization='imagenet',output='cls_token')


def specialized(kind,payload=b'interface metadata only'):
    return Definition(profile=kind+'-reid',adapter='onnx_reid_v1',model_file=kind+'-reid.onnx',
        model_sha256=hashlib.sha256(payload).hexdigest(),dimensions=512,normalization='imagenet',output='embedding',
        preprocessing='roi_resize_256x128_rgb_v1' if kind=='person' else 'roi_resize_256x256_rgb_v1',target_types=[kind])


def native(kind,payload=b'native interface contract'):
    return Definition(profile=kind+'-osnet',adapter='onnx_osnet_v1',model_file=kind+'-osnet.onnx',
        model_sha256=hashlib.sha256(payload).hexdigest(),dimensions=256 if kind=='person' else 512,
        normalization='raw_0_255',output='embedding',target_types=[kind],
        preprocessing='roi_resize_256x128_bgr_v1' if kind=='person' else 'roi_resize_208x208_rgb_v1')


@pytest.mark.parametrize('kind,size,channels',[
    ('person',(1,3,256,128),[251,17,3]),('vehicle',(1,3,208,208),[3,17,251]),
])
def test_native_pixel_protocol_preserves_vendor_channel_order_and_raw_range(kind,size,channels):
    # Tensor arithmetic only, not a generated scene or a model-accuracy test.
    definition=native(kind)
    tensor=pixels(Image.new('RGB',(2,2),(3,17,251)),definition.normalization,definition.preprocessing)
    assert tensor.shape==size and tensor.dtype==np.float32
    assert tensor[0,:,0,0].tolist()==channels
    with pytest.raises(ValueError):Definition.model_validate({**definition.model_dump(),'normalization':'imagenet'})
    with pytest.raises(ValueError):Definition.model_validate({**definition.model_dump(),'target_types':['vehicle' if kind=='person' else 'person']})


def test_dynamic_vehicle_graph_is_checked_against_actual_runtime_embedding(tmp_path,monkeypatch):
    payload=b'native interface contract';definition=native('vehicle',payload)
    (tmp_path/definition.model_file).write_bytes(payload);output_shape=[512]
    class Session:
        def __init__(self,*args,**kwargs):pass
        def get_inputs(self):return [SimpleNamespace(name='input',shape=['n','c','h','w'],type='tensor(float)')]
        def get_outputs(self):return [SimpleNamespace(name='output',shape=['n','dim'],type='tensor(float)')]
        def run(self,names,values):
            assert names==['output'] and values['input'].shape==(1,3,208,208)
            return [np.ones((1,output_shape[0]),dtype=np.float32)]
    import sys
    monkeypatch.setitem(sys.modules,'onnxruntime',SimpleNamespace(SessionOptions=lambda:SimpleNamespace(),InferenceSession=Session))
    encoder=Encoder(tmp_path,definition);vector,_=encoder.encode_crop(Image.new('RGB',(2,2)))
    assert len(vector)==512 and abs(sum(x*x for x in vector)-1)<1e-5
    output_shape[0]=256
    with pytest.raises(ValueError,match='shape'):encoder.encode_crop(Image.new('RGB',(2,2)))


def document():
    return dict(schema_version='appearance-catalog-v1',revision=1,active='general',
        profiles=[general().model_dump(),specialized('person').model_dump(),specialized('vehicle').model_dump()])


def test_legacy_digest_and_type_specific_hot_switch_preserve_frozen_definitions(tmp_path):
    original=Catalog(Path(__file__).parents[1]/'deploy/appearance-catalog.json').definition()
    assert original.sha256=='68a93a786848c4e99eb969bc081413a0560a21952f9f9ab55fa129c27a1efdd5'
    assert 'target_types' not in original.model_dump()
    path=tmp_path/'catalog.json';value=document();path.write_text(json.dumps(value));catalog=Catalog(path)
    frozen=catalog.definition(target_type='person');digest=frozen.sha256
    value.update(revision=2,active_by_type={'person':'person-reid','vehicle':'vehicle-reid'});path.write_text(json.dumps(value))
    assert catalog.definition(target_type='person').profile=='person-reid'
    assert catalog.definition(target_type='vehicle').profile=='vehicle-reid'
    assert catalog.definition().sha256==digest and frozen.profile=='general'
    with pytest.raises(ValueError):catalog.definition('person-reid',target_type='vehicle')
    value['active_by_type']['person']='general';path.write_text(json.dumps(value))
    with pytest.raises(ValueError):catalog.current()
    value['revision']=3;path.write_text(json.dumps(value));assert catalog.definition(target_type='person').sha256==digest
    value['revision']=4;value['profiles'][1]['model_sha256']='c'*64;path.write_text(json.dumps(value))
    with pytest.raises(ValueError):catalog.current()


@pytest.mark.parametrize('changes',[
    {'dimensions':2048},{'output':'arbitrary'},{'target_types':['person','vehicle']},
    {'target_types':None},{'preprocessing':'roi_resize_256x256_rgb_v1'},{'adapter':'python_plugin'},
])
def test_reid_definition_rejects_cross_type_unbounded_or_arbitrary_contracts(changes):
    with pytest.raises(ValueError):Definition.model_validate({**specialized('person').model_dump(),**changes})


def test_typed_catalog_rejects_unknown_or_incompatible_bindings():
    for binding in ({'person':'missing'},{'person':'vehicle-reid'},{'animal':'general'},None):
        with pytest.raises(ValueError):Snapshot.model_validate({**document(),'active_by_type':binding})


def test_reid_encoder_enforces_shape_names_and_l2_without_training_dependencies(tmp_path,monkeypatch):
    # This fake native interface checks tensor/normalization contracts only.
    payload=b'controlled native interface fixture';definition=specialized('person',payload)
    (tmp_path/definition.model_file).write_bytes(payload);seen=[];wrong=[False]
    class Session:
        def __init__(self,*args,**kwargs):pass
        def get_inputs(self):return [SimpleNamespace(name='images',shape=[1,3,256,256 if wrong[0] else 128],type='tensor(float)')]
        def get_outputs(self):return [SimpleNamespace(name='embeddings',shape=[1,512])]
        def run(self,outputs,values):
            assert outputs==['embeddings'] and set(values)=={'images'}
            seen.append(values['images']);return [np.ones((1,512),dtype=np.float32)]
    import sys
    monkeypatch.setitem(sys.modules,'onnxruntime',SimpleNamespace(SessionOptions=lambda:SimpleNamespace(),InferenceSession=Session))
    crop=Image.new('RGB',(20,40),(20,40,60));vector,millis=Encoder(tmp_path,definition).encode_crop(crop)
    assert seen[0].shape==(1,3,256,128) and seen[0].dtype==np.float32
    assert len(vector)==512 and abs(sum(number*number for number in vector)-1)<.00001 and millis>=0
    assert pixels(crop,'imagenet','roi_resize_256x256_rgb_v1').shape==(1,3,256,256)
    wrong[0]=True
    with pytest.raises(ValueError,match='shape'):Encoder(tmp_path,definition)


def test_admin_typed_binding_and_bounded_candidate_backfill_do_not_activate_production(tmp_path):
    value=document();path=tmp_path/'catalog.json';path.write_text(json.dumps(value))
    definitions=[Definition.model_validate(item) for item in value['profiles']]
    for definition in definitions:(tmp_path/definition.model_file).write_bytes(b'interface metadata only')
    metadata={'id':'appearance','available':True,'source_current':True,'revision':0,'base_sha256':'a'*64,
        'bindings':{'appearance':'general'},'profiles':[{'profile':definition.profile,'sha256':definition.sha256,
        'target_types':definition.target_types or ['person','vehicle']} for definition in definitions]}
    cfg=settings();cfg.update(appearance=True,appearance_catalog_file=str(path),appearance_model_dir=str(tmp_path));directory=Directory(cfg);mutations=[]
    def core(request):
        if request.url.path=='/internal/models/local-catalogs':return httpx.Response(200,json={'catalogs':[metadata]})
        if request.url.path in {'/internal/models/local-catalogs/appearance','/internal/appearance/produce'}:
            mutations.append((request.url.path,json.loads(request.content)));return httpx.Response(200,json={'saved':True})
        return directory.handle(request)
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'admin');url='/api/admin/models/local-catalogs/appearance'
        body={'request_id':str(uuid4()),'expected_revision':0,'base_sha256':'a'*64,'bindings':{'appearance':'general','person':'person-reid','vehicle':'vehicle-reid'}}
        assert client.put(url,json=body,headers={'Origin':ORIGIN}).status_code==200
        assert mutations[-1][1]['bindings']==body['bindings']
        bad={**body,'request_id':str(uuid4()),'bindings':{**body['bindings'],'vehicle':'person-reid'}}
        assert client.put(url,json=bad,headers={'Origin':ORIGIN}).status_code==422
        assert client.post('/api/admin/appearance/produce',json={'limit':2,'profiles':['person-reid','vehicle-reid']},headers={'Origin':ORIGIN}).status_code==200
        assert mutations[-1][1]=={'limit':2,'profiles':['person-reid','vehicle-reid']}
        for profiles in (['person-reid','person-reid'],['missing'],['general']*4):
            assert client.post('/api/admin/appearance/produce',json={'limit':2,'profiles':profiles},headers={'Origin':ORIGIN}).status_code==422
        (tmp_path/'vehicle-reid.onnx').unlink()
        assert client.put(url,json={**body,'request_id':str(uuid4())},headers={'Origin':ORIGIN}).status_code==422
    assert len(mutations)==2 and all('control' not in path for path,_ in mutations)
