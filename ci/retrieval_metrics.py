"""Offline identity-retrieval metrics. Never infers truth from tracker/model IDs.

Only explicitly reviewed identities are evaluated. Appearance scores, local
track IDs and matching descriptions are not ground-truth identities. The CLI
reads private JSON manifests; it neither uploads images nor trains models.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics


SHA = re.compile(r'^[a-f0-9]{64}$')
MAX_ITEMS = 50_000


def _identifier(value):
    if not isinstance(value, str) or not 0 < len(value) <= 200:
        raise ValueError('编号无效')
    return value


def _finite(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError('分值或耗时无效')
    return float(value)


def _truth(manifest):
    if manifest.get('schema_version') != 'retrieval-labels-v1':
        raise ValueError('复核集格式不支持')
    review = manifest.get('review', {})
    if not isinstance(review, dict) or review.get('state') != 'reviewed' or not isinstance(review.get('independent'), bool):
        raise ValueError('必须声明已复核状态和独立性')
    _identifier(review.get('reviewer'))
    if manifest.get('identity_scope') not in {'same_camera', 'cross_camera'}:
        raise ValueError('必须声明身份标签适用范围')
    rows = manifest.get('items')
    if not isinstance(rows, list) or not 1 <= len(rows) <= MAX_ITEMS:
        raise ValueError('复核集大小超限')
    items = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('复核条目无效')
        key = _identifier(row.get('id'))
        if key in items:
            raise ValueError('复核条目重复')
        _identifier(row.get('camera_id'))
        if row.get('target_type') not in {'person', 'vehicle'} or row.get('state') not in {'confirmed', 'uncertain'}:
            raise ValueError('目标类型或复核状态无效')
        if not isinstance(row.get('image_sha256'), str) or not SHA.fullmatch(row['image_sha256']):
            raise ValueError('缺少原图摘要')
        box = row.get('bbox_xyxy')
        if not isinstance(box, list) or len(box) != 4 or any(_finite(x) < 0 for x in box) or not box[0] < box[2] or not box[1] < box[3]:
            raise ValueError('目标框无效')
        if row['state'] == 'confirmed':
            _identifier(row.get('identity_id'))
        items[key] = row
    queries, gallery = manifest.get('queries'), manifest.get('gallery')
    for selected in (queries, gallery):
        if not isinstance(selected, list) or not selected or len(selected) != len(set(selected)) or any(key not in items for key in selected):
            raise ValueError('查询集或图库引用无效')
    return items, queries, gallery


def _same_image(left, right):
    # No query-image leakage, including a different crop from the same frame.
    return left['image_sha256'] == right['image_sha256']


def evaluate(manifest, rankings, *, cross_camera=True, accept_threshold=None):
    items, queries, gallery = _truth(manifest)
    if rankings.get('schema_version') != 'retrieval-rankings-v1':
        raise ValueError('检索结果格式不支持')
    runs = rankings.get('runs')
    if not isinstance(runs, list) or not 1 <= len(runs) <= 24:
        raise ValueError('比较方案数量超限')
    if accept_threshold is not None:
        accept_threshold = _finite(accept_threshold)
    report = {
        'schema_version': 'retrieval-evaluation-v1',
        'ground_truth_independent': manifest['review']['independent'],
        'identity_scope': manifest['identity_scope'],
        'cross_camera': cross_camera,
        'identity_accuracy_evaluated': not cross_camera or manifest['identity_scope'] == 'cross_camera',
        'runs': [],
    }
    if not report['identity_accuracy_evaluated']:
        report['reason'] = '当前身份标签只覆盖单镜头，不能计算跨镜身份准确率'
        return report
    run_keys = set()
    for run in runs:
        key = (_identifier(run.get('profile')), _identifier(run.get('strategy')))
        if key in run_keys:
            raise ValueError('比较方案重复')
        run_keys.add(key)
        threshold = run.get('accept_threshold', accept_threshold)
        if threshold is not None:
            threshold = _finite(threshold)
        if not isinstance(run.get('profile_sha256'), str) or not SHA.fullmatch(run['profile_sha256']):
            raise ValueError('缺少特征空间摘要')
        responses = run.get('queries')
        if not isinstance(responses, list) or len(responses) > len(queries):
            raise ValueError('查询结果数量无效')
        by_query = {}
        for response in responses:
            query_id = response.get('query_id')
            if query_id not in queries or query_id in by_query:
                raise ValueError('查询结果引用重复或越界')
            by_query[query_id] = response
        # Missing/failed queries never quietly disappear from the denominator.
        if set(by_query) != set(queries):
            raise ValueError('检索结果缺少查询，不能跳过失败项计算准确率')
        types = {}
        for query_id in queries:
            query, response = items[query_id], by_query[query_id]
            if query['state'] != 'confirmed':
                continue
            stats = types.setdefault(query['target_type'], dict(aps=[], rank1=0, rank5=0,
                closed_queries=0, open_queries=0, false_accepts=0, excluded=0, times=[]))
            if 'elapsed_ms' in response:
                elapsed = _finite(response['elapsed_ms'])
                if elapsed < 0:
                    raise ValueError('耗时无效')
                stats['times'].append(elapsed)
            eligible = {key for key in gallery if items[key]['state'] == 'confirmed'
                and items[key]['target_type'] == query['target_type']
                and not _same_image(query, items[key])
                and ((items[key]['camera_id'] != query['camera_id']) if cross_camera
                     else (items[key]['camera_id'] == query['camera_id']))}
            positives = {key for key in eligible if items[key]['identity_id'] == query['identity_id']}
            hits = response.get('hits')
            if not isinstance(hits, list) or len(hits) > MAX_ITEMS:
                raise ValueError('候选数量无效')
            seen, filtered = set(), []
            previous = math.inf
            for hit in hits:
                identity = hit.get('id')
                score = _finite(hit.get('score'))
                if identity not in gallery or identity in seen or score > previous:
                    raise ValueError('候选越界、重复或未按分值排序')
                seen.add(identity)
                previous = score
                if identity in eligible:
                    filtered.append((identity, score))
                else:
                    stats['excluded'] += 1
            if positives:
                stats['closed_queries'] += 1
                found, precision_sum, first = 0, 0.0, math.inf
                for rank, (identity, _) in enumerate(filtered, 1):
                    if identity in positives:
                        found += 1
                        precision_sum += found / rank
                        first = min(first, rank)
                stats['aps'].append(precision_sum / len(positives))
                stats['rank1'] += first <= 1
                stats['rank5'] += first <= 5
            else:
                stats['open_queries'] += 1
                stats['false_accepts'] += bool(filtered and threshold is not None
                    and filtered[0][1] >= threshold)
        grouped = {}
        for target_type, stats in types.items():
            closed, opened = stats['closed_queries'], stats['open_queries']
            grouped[target_type] = {
                'closed_queries': closed, 'open_queries': opened,
                'mAP': statistics.mean(stats['aps']) if closed else None,
                'rank1': stats['rank1'] / closed if closed else None,
                'rank5': stats['rank5'] / closed if closed else None,
                'open_set_false_accept_rate': stats['false_accepts'] / opened
                    if opened and threshold is not None else None,
                'accept_threshold': threshold, 'excluded_hits': stats['excluded'],
                'median_query_ms': statistics.median(stats['times']) if stats['times'] else None,
            }
        report['runs'].append(dict(profile=key[0], strategy=key[1],
            profile_sha256=run['profile_sha256'], by_target_type=grouped))
    return report


def _read(path):
    with path.open('rb') as source:
        raw = source.read(32 * 1024 * 1024 + 1)
    if len(raw) > 32 * 1024 * 1024:
        raise ValueError('评测输入超过大小限制')
    return json.loads(raw), hashlib.sha256(raw).hexdigest()


def main():
    parser = argparse.ArgumentParser(description='根据显式复核身份标签比较检索方案，不读取或上传原图')
    parser.add_argument('--labels', required=True, type=Path)
    parser.add_argument('--rankings', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--same-camera', action='store_true')
    parser.add_argument('--accept-threshold', type=float)
    args = parser.parse_args()
    labels, label_sha = _read(args.labels)
    rankings, ranking_sha = _read(args.rankings)
    result = evaluate(labels, rankings, cross_camera=not args.same_camera,
                      accept_threshold=args.accept_threshold)
    result.update(labels_sha256=label_sha, rankings_sha256=ranking_sha)
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(result, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write('\n')
    print(json.dumps({'identity_accuracy_evaluated': result['identity_accuracy_evaluated'],
                      'runs': len(result['runs'])}, ensure_ascii=False))


if __name__ == '__main__':
    main()
