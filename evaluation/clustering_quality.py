"""Compare saved clustering/FAQ artifacts without models or production DB access.

python -m evaluation.clustering_quality --before OLD/dataset --after NEW/dataset \
    --labels labels.json --output comparison.json
Labels: {"pairs":[{"leftLogId":1,"rightLogId":2,"sameIntent":true}],
         "matches":[{"logId":1,"candidateType":"EXPAND"}]}
"""
import argparse
from collections import defaultdict
import csv
import json
from pathlib import Path


def read_csv(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream))


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def summarize(data, labels):
    data = Path(data)
    rows = read_csv(data / 'clusters.csv')
    by_id = {str(row['logId']): int(row['clusterId']) for row in rows}
    if len(by_id) != len(rows):
        raise ValueError('Duplicate clustered log IDs')
    groups = defaultdict(set)
    for row in rows:
        groups[row['text']].add(int(row['clusterId']))
    prepared = read_csv(data / 'preprocessed_full.csv')
    input_ids = {str(row['logId']) for row in prepared}
    if len(input_ids) != len(prepared):
        raise ValueError('Duplicate prepared log IDs')
    if not set(by_id) <= input_ids:
        raise ValueError('Clustered IDs outside prepared input')
    traced = set(by_id)
    if (data / 'unclustered.json').exists():
        traced.update(str(row['sourceLogSeqNum']) for row in json.loads((data / 'unclustered.json').read_text(encoding='utf-8')))
    faq_path = data / 'faq_candidates_final.json'
    if not faq_path.exists():
        faq_path = data / 'faq_candidates.json'
    candidates = json.loads(faq_path.read_text(encoding='utf-8'))['faqCandidates'] if faq_path.exists() else []
    faq_questions = [c['standardQuestion'].strip() for c in candidates if c.get('tag', 'FAQ') == 'FAQ']
    sample_count = sample_duplicates = 0
    checkpoint = data / 'faq_candidates.jsonl'
    if checkpoint.exists():
        for line in checkpoint.read_text(encoding='utf-8').splitlines():
            samples = [' '.join(s.split()) for s in json.loads(line).get('samples', [])]
            sample_count += len(samples)
            sample_duplicates += len(samples) - len(set(samples))
    false_merges = merges = true_merges = positive_pairs = evaluated = 0
    for pair in labels.get('pairs', []):
        left, right = str(pair['leftLogId']), str(pair['rightLogId'])
        if left not in by_id or right not in by_id:
            continue
        evaluated += 1
        merged = by_id[left] != -1 and by_id[left] == by_id[right]
        merges += merged
        positive_pairs += pair['sameIntent'] is True
        true_merges += merged and pair['sameIntent'] is True
        false_merges += merged and pair['sameIntent'] is False
    # Cluster/candidate numbers can change between runs. Label by source log ID.
    matched = []
    for label in labels.get('matches', []):
        log_id = str(label['logId'])
        cluster_id = by_id.get(log_id)
        choices = [c for c in candidates if c.get('tag', 'FAQ') == 'FAQ' and (
            log_id in {str(value) for value in c.get('logIds', [])} or
            cluster_id is not None and cluster_id != -1 and cluster_id in
            c.get('finalClusterIds', [c.get('clusterLabel')]))]
        if len(choices) == 1:
            matched.append((label, choices[0]))
    result = dict(originalLogCount=len(rows), uniqueQuestionCount=len(groups),
                  clusterCount=len(set(by_id.values()) - {-1}),
                  identicalQuestionSplitCount=sum(len(values) > 1 for values in groups.values()),
                  identicalQuestionConsistency=ratio(sum(len(v) == 1 for v in groups.values()), len(groups)),
                  originalNoiseRate=ratio(sum(v == -1 for v in by_id.values()), len(rows)),
                  uniqueNoiseRate=ratio(sum(v == {-1} for v in groups.values()), len(groups)),
                  logPreservationRate=ratio(len(traced & input_ids), len(input_ids)),
                  exactDuplicateFaqCount=len(faq_questions) - len(set(faq_questions)),
                  sampleDuplicateRate=ratio(sample_duplicates, sample_count),
                  evaluatedPairCount=evaluated, suppliedPairCount=len(labels.get('pairs', [])),
                  falseMergeRate=ratio(false_merges, merges),
                  sameIntentMergeRecall=ratio(true_merges, positive_pairs),
                  evaluatedMatchCount=len(matched), suppliedMatchCount=len(labels.get('matches', [])),
                  expansionAccuracy=ratio(sum(a['candidateType'] == b.get('candidateType') for a, b in matched), len(matched)))
    performance = data.parent / 'performance.json'
    if performance.exists():
        measured = json.loads(performance.read_text(encoding='utf-8'))
        result['resources'] = {key: measured.get(key) for key in (
            'elapsedSeconds', 'cpuPeakRssBytes', 'gpuPeakAllocatedBytes', 'gpuPeakReservedBytes')}
    return result, {str(row['logId']): row['normalizedForClustering'] for row in prepared}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--labels', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--accepted', type=int, default=0)
    parser.add_argument('--reviewed', type=int, default=0)
    args = parser.parse_args()
    if not 0 <= args.accepted <= args.reviewed:
        parser.error('Require 0 <= accepted <= reviewed')
    labels = json.loads(args.labels.read_text(encoding='utf-8')) if args.labels else {}
    before, old_ids = summarize(args.before, labels)
    after, new_ids = summarize(args.after, labels)
    if old_ids != new_ids:
        raise ValueError('Comparison requires identical selected input log IDs')
    args.output.write_text(json.dumps(dict(before=before, after=after,
        faqAdoptionRate=ratio(args.accepted, args.reviewed), reviewedCount=args.reviewed,
        note='Null means unmeasured; exact duplicates do not measure semantic duplicates.'),
        ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
