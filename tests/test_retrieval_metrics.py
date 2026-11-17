"""Arithmetic and truth-provenance contracts; these are not visual test data."""
import pytest
from ci.retrieval_metrics import evaluate


def fixture():
    def item(key, identity, camera, sha):
        return dict(id=key, identity_id=identity, camera_id=camera, target_type='person',
                    state='confirmed', image_sha256=sha * 64, bbox_xyxy=[0, 0, 20, 40])
    labels = dict(schema_version='retrieval-labels-v1', identity_scope='cross_camera',
        review=dict(state='reviewed', independent=False, reviewer='contract-reviewer'),
        items=[item('q', 'a', 'one', 'a'), item('open', 'absent', 'one', 'b'),
               item('positive', 'a', 'two', 'c'), item('negative', 'b', 'two', 'd'),
               item('leak', 'a', 'two', 'a'), item('local', 'a', 'one', 'e')],
        queries=['q', 'open'], gallery=['positive', 'negative', 'leak', 'local'])
    rankings = dict(schema_version='retrieval-rankings-v1', runs=[dict(profile='model',
        profile_sha256='f' * 64, strategy='appearance-v1', queries=[
            dict(query_id='q', elapsed_ms=10, hits=[dict(id='leak', score=1),
                dict(id='local', score=.99), dict(id='negative', score=.9), dict(id='positive', score=.8)]),
            dict(query_id='open', elapsed_ms=20, hits=[dict(id='negative', score=.9)])])])
    return labels, rankings


def test_metrics_exclude_same_frame_and_camera_and_separate_open_set():
    labels, rankings = fixture()
    result = evaluate(labels, rankings, accept_threshold=.85)
    score = result['runs'][0]['by_target_type']['person']
    assert result['ground_truth_independent'] is False
    assert score['closed_queries'] == score['open_queries'] == 1
    assert score['mAP'] == .5 and score['rank1'] == 0 and score['rank5'] == 1
    assert score['open_set_false_accept_rate'] == 1
    assert score['excluded_hits'] == 2 and score['median_query_ms'] == 15


def test_local_track_labels_cannot_be_promoted_to_cross_camera_accuracy():
    labels, rankings = fixture()
    labels['identity_scope'] = 'same_camera'
    result = evaluate(labels, rankings)
    assert result['identity_accuracy_evaluated'] is False and result['runs'] == []
    score = evaluate(labels, rankings, cross_camera=False)['runs'][0]['by_target_type']['person']
    assert score['mAP'] == score['rank1'] == 1


def test_missing_failed_queries_and_duplicate_hits_never_improve_denominator():
    labels, rankings = fixture()
    rankings['runs'][0]['queries'].pop()
    with pytest.raises(ValueError, match='缺少查询'):
        evaluate(labels, rankings)
    labels, rankings = fixture()
    rankings['runs'][0]['queries'][0]['hits'].append(dict(id='positive', score=.7))
    with pytest.raises(ValueError, match='重复'):
        evaluate(labels, rankings)


def test_uncertain_labels_are_not_negative_truth_and_nan_is_rejected():
    labels, rankings = fixture()
    labels['items'][3]['state'] = 'uncertain'
    score = evaluate(labels, rankings)['runs'][0]['by_target_type']['person']
    assert score['mAP'] == score['rank1'] == 1
    rankings['runs'][0]['queries'][0]['hits'][0]['score'] = float('nan')
    with pytest.raises(ValueError, match='无效'):
        evaluate(labels, rankings)


def test_unreviewed_truth_and_unversioned_spaces_are_rejected():
    labels, rankings = fixture()
    labels['review']['state'] = 'automatic_tracker'
    with pytest.raises(ValueError, match='复核'):
        evaluate(labels, rankings)
    labels, rankings = fixture()
    rankings['runs'][0]['profile_sha256'] = 'missing'
    with pytest.raises(ValueError, match='摘要'):
        evaluate(labels, rankings)
