from fractions import Fraction
from types import SimpleNamespace
import pytest
from services.video_worker.config import Settings
from services.video_worker.recording_control import RecordingControl,RecordingSlot


def snapshot(version=1,enabled=False,plugin='short_segment_v1',seconds=5):
    return {'schema_version':'pipeline-features-v1','lease_seconds':15,'features':[{'id':'frame_recording','version':version,
        'enabled':enabled,'plugin':plugin,'parameters':{'seconds':seconds} if plugin=='context_window_v1' else {}}]}


def test_storage_producers_are_opt_in_by_default(monkeypatch):
    for key in ('LIVE_RECORDING_ENABLED','LIVE_RECORDING_CONTEXT_SECONDS','PIPELINE_FEATURES_ENABLED','MEDIA_JOBS_ENABLED','EXPORTS_ENABLED','SCENE_AUDIT_ENABLED'):monkeypatch.delenv(key,raising=False)
    settings=Settings.from_env()
    assert not settings.live_recording_enabled and settings.live_recording_context_seconds==0
    assert not settings.pipeline_features_enabled and not settings.media_jobs_enabled and not settings.exports_enabled and not settings.scene_audit_enabled


def test_lease_expiry_and_invalid_policy_do_not_enable_recording():
    now=[100.];control=RecordingControl(lambda:now[0]);assert not control.current().enabled
    assert control.accept(snapshot(enabled=True,plugin='context_window_v1'))
    assert control.current().seconds==5 and control.current().enabled
    now[0]+=16;assert not control.current().enabled
    assert not control.accept(snapshot(enabled=True,plugin='context_window_v1'))
    assert control.current().enabled
    with pytest.raises(ValueError):control.accept(snapshot(enabled=False))
    control.invalidate();assert not control.current().enabled
    with pytest.raises(ValueError):control.accept(snapshot(version=0))


@pytest.mark.parametrize('change',[{'enabled':'true'},{'version':True},{'plugin':'arbitrary_code'},
    {'plugin':'context_window_v1','parameters':{'seconds':True}},{'plugin':'context_window_v1','parameters':{'seconds':5.0}},
    {'parameters':{'extra':1}},{'plugin':'context_window_v1','parameters':{'seconds':60}}])
def test_unknown_or_ambiguous_plugins_are_rejected(change):
    value=snapshot();value['features'][0].update(change)
    with pytest.raises(ValueError):RecordingControl().accept(value)


def test_hot_unplug_never_drains_the_live_decoder_or_creates_files_when_off(tmp_path,monkeypatch):
    calls=[]
    class Recorder:
        def __init__(self,settings,*args):calls.append(('open',settings.live_recording_context_seconds))
        def packet(self,*args):calls.append(('packet',))
        def discontinuity(self,*args):calls.append(('detach',))
        def close(self):calls.append(('close',))
        def finish_decoding(self):calls.append(('drain',))
        def binding(self,*args):return {'present':True}
    monkeypatch.setattr('services.video_worker.live_recording.NativeRecorder',Recorder)
    settings=Settings('https://core.invalid','unused','unused',live_recording_dir=tmp_path/'absent')
    reader=SimpleNamespace(settings=settings,recording_context={},camera='camera-contract',run_id='run',epoch=0,add=lambda *args:None)
    container=SimpleNamespace(streams=SimpleNamespace(video=[SimpleNamespace(codec_context=SimpleNamespace(name='h264'),time_base=Fraction(1,90000))]))
    control=RecordingControl();slot=RecordingSlot(reader,container,control);slot.packet(None,0)
    assert not calls and not settings.live_recording_dir.exists() and slot.binding(0,0) is None
    control.accept(snapshot(enabled=True));slot.packet(None,0);assert slot.binding(0,0)
    control.accept(snapshot(version=2,enabled=True,plugin='context_window_v1',seconds=3));slot.packet(None,0)
    assert ('open',0) in calls and ('open',3) in calls and ('drain',) not in calls
    control.accept(snapshot(version=3));slot.packet(None,0);slot.close()
    assert calls.count(('close',))==2 and ('drain',) not in calls
    control.accept(snapshot(version=4,enabled=True));slot.packet(None,0);slot.close()
    assert calls[-2:]==[('drain',),('close',)]


def test_unavailable_optional_store_retries_with_a_bound_without_stopping_capture(monkeypatch):
    now=[100.];attempts=[];control=RecordingControl(lambda:now[0]);control.accept(snapshot(enabled=True))
    def unavailable(*args):attempts.append(True);raise OSError('unavailable')
    monkeypatch.setattr('services.video_worker.live_recording.NativeRecorder',unavailable)
    reader=SimpleNamespace(settings=Settings('https://core.invalid','unused','unused'),recording_context={},camera='camera-contract',run_id='run',epoch=0,add=lambda *args:None)
    container=SimpleNamespace(streams=SimpleNamespace(video=[SimpleNamespace(codec_context=SimpleNamespace(name='h264'),time_base=Fraction(1,90000))]))
    slot=RecordingSlot(reader,container,control);slot.packet(None,0);slot.packet(None,0)
    assert len(attempts)==1 and slot.recorder is None
    now[0]+=31;control.accept(snapshot(enabled=True));slot.packet(None,0)
    assert len(attempts)==2
