"""Nonvisual clock, storage and compatibility contracts; no scene-accuracy fixture."""
from collections import Counter
from uuid import uuid4
import pytest
from services.video_worker.recording_windows import RecordingWindows
from services.video_worker.live_recording_store import LiveStore,namespace_for
from services.video_worker import session_media
from test_session_media import descriptor


@pytest.mark.parametrize('seconds',[3,5,10])
def test_windows_share_by_time_group_and_cover_both_sides_after_warmup(seconds):
    planner=RecordingWindows(seconds,5);bound=[];closed={};previous={}
    # Irregular GOPs exercise handover, independently of selected-frame rate.
    keyframes={0,13,26,39,52,65,78,91,104,117,130}
    for second in range(131):
        windows=planner.advance(second in keyframes,second*90000,second*1_000_000,second*1_000_000)
        assert len(windows)<=2
        current={window.identity:window for window in windows}
        for identity in previous.keys()-current.keys():closed[identity]=second*1_000_000
        reference=planner.binding_window(second*1_000_000)
        if reference and second>=seconds:bound.append((second*1_000_000,reference))
        previous=current
    for pts,window in bound:
        if window.identity not in closed:continue
        assert pts-window.base_pts_us>=seconds*1_000_000
        assert closed[window.identity]-pts>=seconds*1_000_000
    counts=Counter(window.identity for _,window in bound)
    assert max(counts.values())>seconds


def test_legacy_window_rotation_and_epoch_reset_do_not_reuse_old_context():
    planner=RecordingWindows(0,5)
    assert planner.advance(False,0,0,0)==()
    first=planner.advance(True,100,1_000_000,1_000_000)[0]
    assert planner.advance(True,200,3_000_000,3_000_000)==(first,)
    second=planner.advance(True,300,6_000_000,6_000_000)[0]
    assert second.identity!=first.identity
    planner.clear();assert planner.binding_window(9_000_000) is None
    assert planner.advance(False,400,9_000_000,9_000_000)==()


def test_reference_prevents_pruning_and_quota_is_reserved_before_recording(tmp_path):
    identity=str(uuid4());namespace=namespace_for('contract-worker')
    store=LiveStore(tmp_path,namespace,writer=True,limit=256*1024*1024)
    try:
        store.reserve(identity,{'media_epoch':0})
        frame=str(uuid4());reference={'namespace':namespace,'segment_id':identity,'source_pts_us':1,'packet_sha256':'b'*64}
        assert store.pin(frame,'a'*64,reference)
        with pytest.raises(ValueError,match='reference_conflict'):store.pin(frame,'c'*64,reference)
        store.reserve(str(uuid4()),{'media_epoch':0})
        with pytest.raises(ValueError,match='capacity_reached'):store.reserve(str(uuid4()),{'media_epoch':0})
    finally:store.close()


def test_extended_session_descriptor_is_explicit_and_legacy_keeps_its_limits():
    value=descriptor();value['duration_us']=40_000_000
    with pytest.raises(ValueError):session_media.validate_descriptor(value)
    value['version']=session_media.CONTEXT_VERSION;value['source_bytes']=40*1024*1024
    session_media.validate_descriptor(value)
    value['duration_us']=75_000_001
    with pytest.raises(ValueError):session_media.validate_descriptor(value)
    value['duration_us']=40_000_000;value['source_bytes']=128*1024*1024+1
    with pytest.raises(ValueError):session_media.validate_descriptor(value)
