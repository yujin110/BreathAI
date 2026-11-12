"""Versioned, model-free selection and artifact validation for analysis jobs."""
from collections import Counter
from datetime import datetime
import csv
import json
from pathlib import Path

from services.analysis_input import read_analysis_csv, write_analysis_csv
from services.log_classification import log_type, classification_source

MODES = ('IMPROVEMENT', 'UNANSWER_ONLY', 'ALL')
FAQ_MIN_CLUSTER_SIZE = 5
SOURCES = ('EXPLICIT_LOG_TYPE', 'KEYWORD_MATCH_PERCENT', 'AI_PERCENT', 'LEGACY_DEFAULT')
PREPARED_COLUMNS = ['logId', 'date', 'originalText', 'lang', 'normalizedForClustering',
                    'normalizedForGeneration', 'extractedEntities', 'labelValid', 'labelType',
                    'labelKeywords', 'labelAnswerDirection', 'labelMatchProbability', 'logType']


def validate_mode(mode):
    if mode not in MODES:
        raise ValueError('analysisMode must be IMPROVEMENT, UNANSWER_ONLY or ALL')
    return mode


def selected(label, mode):
    validate_mode(mode)
    return mode == 'ALL' or label == 'UNANSWER' or (mode == 'IMPROVEMENT' and label == 'LOW_QUALITY')


def write_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding='utf-8')
    temporary.replace(path)


def write_csv(path, fields, rows):
    with Path(path).open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def read_csv(path):
    with Path(path).open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream))


def prepare_selection(data, mode):
    validate_mode(mode)
    fields, rows = read_analysis_csv(data / 'tota24_unanswered_2026Q1.csv')
    # Import here to avoid an intake/metrics import cycle.
    from services.dashboard_metrics import validate_source_rows
    from services.analysis_progress import report
    validate_source_rows(rows)
    report('CLASSIFYING', 0, len(rows), 'LOG')
    from services.log_classification import LOG_TYPES
    counts, daily = Counter(), {}
    for row in rows:
        label = log_type(row)
        counts[LOG_TYPES[label]] += 1
        day = datetime.fromisoformat(row['날짜'].strip()).date().isoformat()
        bucket = daily.setdefault(day, Counter())
        bucket['totalCnt'] += 1
        bucket[{'CORRECT': 'answerCnt', 'LOW_QUALITY': 'lowQualityCount', 'UNANSWER': 'unanswerCnt'}[label]] += 1
    write_json(data / 'input_statistics.json', {
        'totalLogCount': len(rows), 'samplingStatus': {key: counts[key] for key in LOG_TYPES.values()},
        'currentUnanswerRate': round(100 * counts['unanswer'] / len(rows), 6),
        'usageStats': [{'statDate': day, **{key: values[key] for key in
                        ('totalCnt', 'answerCnt', 'lowQualityCount', 'unanswerCnt')}}
                       for day, values in sorted(daily.items())],
    })
    targets, manifest = [], []
    for row in rows:
        label = log_type(row)
        include = selected(label, mode)
        manifest.append({'sourceLogSeqNum': int(row['번호']), 'date': row['날짜'],
                         'logType': label, 'classificationSource': classification_source(row),
                         'isTarget': include, 'exclusionReason': None if include else 'EXCLUDED_BY_MODE'})
        if include:
            targets.append({**row, 'logType': label})
    write_analysis_csv(data / 'analysis_targets.csv', list(dict.fromkeys([*fields, 'logType'])), targets)
    with (data / 'classification_manifest.jsonl').open('w', encoding='utf-8') as stream:
        for item in manifest:
            stream.write(json.dumps(item, ensure_ascii=False) + '\n')
    metadata = {'artifactSchemaVersion': 2, 'classificationPolicyVersion': 'match-percent-v1',
                'analysisMode': mode, 'minClusterSize': None, 'minSamples': None}
    write_json(data / 'analysis_metadata.json', metadata)
    report('CLASSIFYING', len(rows), len(rows), 'LOG')
    return targets


def empty_clusters(data, policy):
    from services.keyword_policy import write_audit
    write_csv(data / 'clusters.csv', ['logId', 'clusterId', 'umap_x', 'umap_y', 'text'], [])
    write_csv(data / 'cluster_summary.csv', ['clusterId', 'size', 'isNoise', 'topKeywords'], [])
    write_json(data / 'cluster_keywords.json', {})
    write_audit(data / 'keyword_policy_audit.json', policy, 0, {})


def finalize_selection(data, *, min_cluster_size, min_samples, faq_min_size):
    """Derive scope from actual artifacts; never trust cached counters."""
    metadata = json.loads((data / 'analysis_metadata.json').read_text(encoding='utf-8'))
    mode = validate_mode(metadata['analysisMode'])
    _, rows = read_analysis_csv(data / 'tota24_unanswered_2026Q1.csv')
    originals = {int(row['번호']): row for row in rows}
    targets = {key for key, row in originals.items() if selected(log_type(row), mode)}
    manifest = [json.loads(line) for line in (data / 'classification_manifest.jsonl').read_text(encoding='utf-8').splitlines()]
    manifest_by_id = {row['sourceLogSeqNum']: row for row in manifest}
    if len(manifest) != len(originals) or len(manifest_by_id) != len(originals) or manifest_by_id.keys() != originals.keys():
        raise ValueError('Classification manifest IDs mismatch')
    for key, row in originals.items():
        item = manifest_by_id[key]
        if (item['logType'] != log_type(row) or item['classificationSource'] != classification_source(row) or
                item['isTarget'] != (key in targets) or item['date'] != row['날짜']):
            raise ValueError('Classification manifest differs from input')
    _, target_rows = read_analysis_csv(data / 'analysis_targets.csv')
    if len(target_rows) != len(targets) or {int(row['번호']) for row in target_rows} != targets:
        raise ValueError('Target artifact IDs mismatch')
    prepared_rows = read_csv(data / 'preprocessed_full.csv')
    prepared = {int(row['logId']): row for row in prepared_rows}
    if len(originals) != len(rows) or len(prepared) != len(prepared_rows) or prepared.keys() != targets:
        raise ValueError('Selected/preprocessed IDs do not match input')
    for key, row in prepared.items():
        if row['logType'] != log_type(originals[key]):
            raise ValueError('Preprocessed classification changed')
    eligible = {key for key, row in prepared.items() if len(row['normalizedForClustering']) >= 2}
    clustered = read_csv(data / 'clusters.csv')
    ids = [int(row['logId']) for row in clustered]
    minimum = max(3, min_cluster_size, min_samples + 1)
    # Accept legacy deferred artifacts; current clustering preserves small inputs
    # as individual question groups mapped to noise, with real log provenance.
    deferred = len(eligible) if len(eligible) < minimum and not clustered else 0
    expected = set() if deferred else eligible
    if len(set(ids)) != len(ids) or set(ids) != expected:
        raise ValueError('Cluster IDs do not cover selected eligible input')
    summaries = read_csv(data / 'cluster_summary.csv')
    labels = Counter(int(row['clusterId']) for row in clustered)
    summary_counts = {int(row['clusterId']): int(row['size']) for row in summaries}
    if len(summary_counts) != len(summaries) or dict(labels) != summary_counts:
        raise ValueError('Cluster summary mismatch')
    faq_count = sum(label != -1 and size >= faq_min_size for label, size in labels.items())
    sources = Counter(classification_source(row) for row in rows)
    scope = dict(totalLogCount=len(rows), targetLogCount=len(targets),
                 excludedByModeCount=len(rows)-len(targets), excludedByTextCount=len(targets)-len(eligible),
                 eligibleLogCount=len(eligible), clusteredLogCount=len(ids), deferredSmallInputCount=deferred,
                 noiseLogCount=labels[-1], visualizedLogCount=len(ids), faqClusterCount=faq_count,
                 classificationSourceCounts={key: sources[key] for key in SOURCES}, keywordScope='ANALYSIS_TARGET')
    reason = ('NO_TARGET_LOGS' if not targets else 'NO_ELIGIBLE_TEXT' if not eligible else
              'INSUFFICIENT_TARGET_LOGS' if deferred else 'NO_FAQ_CLUSTERS' if not faq_count else 'RESULTS_GENERATED')
    metadata.update(minClusterSize=min_cluster_size, minSamples=min_samples, faqMinSize=faq_min_size)
    write_json(data / 'analysis_metadata.json', metadata)
    result = dict(resultSchemaVersion=2, analysisMode=mode, completionReason=reason, analysisScope=scope)
    write_json(data / 'analysis_scope.json', result)
    # Keep masked/normalized text only; no fake coordinates for deferred logs.
    label_by_id = {int(row['logId']): int(row['clusterId']) for row in clustered}
    unclustered = [dict(sourceLogSeqNum=key, logType=row['logType'],
                        reason='EMPTY_OR_TOO_SHORT' if key not in eligible else
                        'INSUFFICIENT_TARGET_LOGS' if deferred else 'NOISE' if label_by_id.get(key) == -1 else 'BELOW_FAQ_MIN_SIZE',
                        normalizedText=row['normalizedForClustering'])
                   for key, row in prepared.items() if key not in eligible or deferred or
                   label_by_id.get(key) == -1 or labels[label_by_id[key]] < faq_min_size]
    write_json(data / 'unclustered.json', unclustered)
    for key, item in manifest_by_id.items():
        item['exclusionReason'] = ('EXCLUDED_BY_MODE' if key not in targets else
                                   'EMPTY_OR_TOO_SHORT' if key not in eligible else
                                   'INSUFFICIENT_TARGET_LOGS' if deferred else None)
    with (data / 'classification_manifest.jsonl').open('w', encoding='utf-8') as stream:
        for item in manifest:
            stream.write(json.dumps(item, ensure_ascii=False) + '\n')
    return result
