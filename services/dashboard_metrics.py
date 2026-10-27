"""Measured dashboard data scoped to the period-filtered upload, never service traffic."""
from collections import Counter, defaultdict
import csv
from datetime import datetime, timedelta
import math
from services.keyword_policy import get_policy, KOREAN_TOKEN


LOG_TYPES = {'CORRECT': 'correct', 'LOW_QUALITY': 'lowQuality', 'UNANSWER': 'unanswer'}


def read_csv(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        reader.fieldnames = [name.replace('\n', '') for name in (reader.fieldnames or [])]
        return list(reader)


def source_id(value):
    # CSV 번호 is the upload-local source identifier, NOT an invented Bronze PK.
    number = int(value)
    if not -(2 ** 31) <= number < 2 ** 31:
        raise ValueError('Source log ID exceeds BE INT range')
    return number


def log_type(row):
    # This endpoint ingests unanswered-learning files. Validity/answer probability
    # are training labels, not an observed chatbot response outcome.
    value = (row.get('logType') or 'UNANSWER').strip()
    if value not in LOG_TYPES:
        raise ValueError('logType must be CORRECT, LOW_QUALITY or UNANSWER')
    return value


def validate_source_rows(rows):
    if not rows:
        raise ValueError('No input rows')
    seen = set()
    for row in rows:
        key = source_id(row['번호'])
        if key in seen:
            raise ValueError('Duplicate source log ID within upload')
        seen.add(key)
        datetime.fromisoformat(row['날짜'].strip())
        log_type(row)


def build_dashboard(data, keywords, summaries, *, policy=None):
    rows = read_csv(data / 'tota24_unanswered_2026Q1.csv')
    validate_source_rows(rows)
    preprocessed = read_csv(data / 'preprocessed_full.csv')
    clustered = read_csv(data / 'clusters.csv')
    originals = {}
    daily = defaultdict(Counter)
    counts = Counter()
    dates = {}
    for row in rows:
        key = source_id(row['번호'])
        if key in originals:
            raise ValueError('Duplicate source log ID within upload')
        originals[key] = row
        kind = log_type(row)
        counts[LOG_TYPES[kind]] += 1
        day = datetime.fromisoformat(row['날짜'].strip()).date()
        dates[key] = day
        daily[day]['totalCnt'] += 1
        daily[day][{'CORRECT': 'answerCnt', 'LOW_QUALITY': 'lowQualityCount', 'UNANSWER': 'unanswerCnt'}[kind]] += 1
    prepared = {source_id(row['logId']): row for row in preprocessed}
    if len(prepared) != len(preprocessed) or prepared.keys() != originals.keys():
        raise ValueError('Preprocessed log IDs do not match input')
    points, seen, cluster_counts = [], set(), Counter()
    for row in clustered:
        key = source_id(row['logId'])
        label = int(row['clusterId'])
        if key not in originals or key in seen or label not in summaries:
            raise ValueError('Invalid clustered source ID or label')
        x, y = float(row['umap_x']), float(row['umap_y'])
        if not math.isfinite(x) or not math.isfinite(y):
            raise ValueError('Non-finite visualization coordinate')
        # Use the actual normalized/masked artifact, never raw uploaded questions.
        points.append({'sourceLogSeqNum': key, 'clusterLabel': label, 'x': x, 'y': y,
                       'text': prepared[key]['normalizedForClustering'], 'logType': log_type(originals[key])})
        seen.add(key)
        cluster_counts[label] += 1
    if any(cluster_counts[label] != int(row['size']) for label, row in summaries.items()):
        raise ValueError('Cluster summary counts do not match points')
    expected = {key for key, row in prepared.items() if len(row['normalizedForClustering']) >= 2}
    if seen != expected:
        raise ValueError('Coordinates must cover every cluster-eligible log; sampling is not enabled')
    usage = [{'statDate': day.isoformat(), **{name: daily[day][name] for name in
              ('totalCnt', 'answerCnt', 'lowQualityCount', 'unanswerCnt')}} for day in sorted(daily)]
    total = len(rows)
    burst, surge = _burst_keywords(points, dates, keywords, dates.values(), policy=policy)
    return {
        'metricsScope': 'ANALYSIS_INPUT', 'sourceLogIdScope': 'UPLOAD_LOCAL',
        'systemStatus': {'totalLogCount': total,
                         'samplingStatus': {name: counts[name] for name in LOG_TYPES.values()},
                         'visualizedLogCount': len(points), 'excludedLogCount': total - len(points),
                         'isSampled': False},
        # Original input outcomes BEFORE any generated FAQ is adopted. Percent,
        # not a 0..1 ratio and not an estimated post-improvement success rate.
        'performanceMetrics': {'currentUnanswerRate': round(100 * counts['unanswer'] / total, 6) if total else None,
                               'predictedAccuracyGain': None, 'resolvedCountByAI': None},
        'usageStats': usage,
        'trend': [{'date': row['statDate'], 'lowQualityCount': row['lowQualityCount'],
                   'unanswerCount': row['unanswerCnt']} for row in usage],
        'burstKeywords': burst, 'surgeDetection': surge,
        'points': points,
    }


def _burst_keywords(points, dates, keywords, input_dates, *, policy=None):
    """Last 7 vs preceding 7 calendar days within the upload (>=3, >=100% rise).

    Require an observed date in the previous window; no historical baseline is
    fabricated for single-day uploads. Count a keyword at most once per log.
    """
    policy = policy or get_policy()
    days = list(input_dates)
    if not days:
        return [], []
    end = max(days)
    current_start, previous_start = end - timedelta(days=6), end - timedelta(days=13)
    if not any(previous_start <= day < current_start for day in days):
        return [], []
    vocabulary = sorted({canonical for words in keywords.values() for word in words
                         if (canonical := policy.candidate(word)) is not None})
    hits = {word: Counter() for word in vocabulary}
    related = {word: Counter() for word in vocabulary}
    for point in points:
        day = dates[point['sourceLogSeqNum']]
        if not previous_start <= day <= end:
            continue
        english = set(policy.english_tokens(point['text'])[0])
        for word in vocabulary:
            matched = (word.casefold() in point['text'].casefold()
                       if KOREAN_TOKEN.search(word) else word in english)
            if matched:
                hits[word][day] += 1
                related[word][point['clusterLabel']] += 1
    bursts, surges = [], []
    for word in vocabulary:
        current = sum(n for day, n in hits[word].items() if day >= current_start)
        previous = sum(n for day, n in hits[word].items() if day < current_start)
        if current < 3 or (previous and current < 2 * previous):
            continue
        rate = round(100 * (current - previous) / previous, 6) if previous else None
        bursts.append({'keyword': word, 'increasedRate': rate, 'count': current})
        surges.append({'keyword': word, 'isNew': previous == 0, 'increasedRate': rate,
                       'keywordCount': current, 'relatedClusterLabel': related[word].most_common(1)[0][0],
                       'periodStats': [
                           {'statDate': day.isoformat(), 'periodType': 'BASE' if day >= current_start else 'PREV',
                            'count': hits[word][day]}
                           for day in (previous_start + timedelta(days=i) for i in range(14))]})
    return bursts, surges
