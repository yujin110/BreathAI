"""Recompute paired pilot metrics using explicit, separately reviewed labels."""
import argparse
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from services.synonym_validation import validate_synonyms
from evaluation.evaluate_synonym_types import parse_generated


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    out = args.output
    manifest = json.loads((out / 'manifest.json').read_text(encoding='utf-8'))
    policy = manifest['policy']
    snapshot = out / 'validator-snapshot.py'
    if snapshot.exists() and hashlib.sha256(snapshot.read_bytes()).digest() != hashlib.sha256(
            (ROOT / 'services/synonym_validation.py').read_bytes()).digest():
        raise ValueError('Validator differs from evaluation snapshot')
    records = [json.loads(line) for line in (out / 'generations.jsonl').read_text(
        encoding='utf-8').splitlines()] if (out / 'generations.jsonl').exists() else []
    input_data = out / 'inputs' if (out / 'inputs').exists() else ROOT / 'dataset'
    cluster_file = input_data / 'clusters.csv'
    if hashlib.sha256(cluster_file.read_bytes()).hexdigest() != manifest['inputSha256']['clusters.csv']:
        raise ValueError('Cluster input differs from evaluation manifest')
    source = list(csv.DictReader(cluster_file.open(encoding='utf-8-sig')))
    # Always recompute from immutable raw text, including records from an earlier
    # pilot parser. Invalid outer JSON is a generation failure, never a label win.
    for record in records:
        record['parsed'] = parse_generated(record['raw'])
        synonyms = (record['parsed'] or {}).get('synonyms', [])
        rows = [r for r in source if int(r['clusterId']) == record['clusterLabel']]
        record['accepted'], record['audit'] = validate_synonyms(synonyms, rows, policy=policy)
    (out / 'rescored-generations.jsonl').write_text(''.join(
        json.dumps(r, ensure_ascii=False) + '\n' for r in records), encoding='utf-8')
    controls = json.loads((out / 'observed-review-controls.json').read_text(encoding='utf-8'))
    control_results = []
    for case in controls['cases']:
        rows = [r for r in source if r['logId'] == case['logId'] and r['text'] == case['text']]
        if not rows:
            raise ValueError('Diagnostic control not found in actual source: ' + case['logId'])
        accepted, audit = validate_synonyms([dict(text=case['text'], type='TYPO')], rows, policy=policy)
        control_results.append(dict(**case, accepted=accepted, audit=audit))
    (out / 'control-results.json').write_text(json.dumps(control_results, ensure_ascii=False, indent=2), encoding='utf-8')
    labels_path = out / 'review-labels.json'
    labels = json.loads(labels_path.read_text(encoding='utf-8')) if labels_path.exists() else []
    indexed = {r['text']: r for r in labels}
    summaries = {}
    review_rows = []
    for variant in ['baseline', 'revised']:
        selected = [r for r in records if r['variant'] == variant]
        raw, accepted = [], []
        for row in selected:
            parsed = row['parsed'] or {}
            if parsed.get('tag') != 'FAQ':
                continue
            for index, item in enumerate(parsed.get('synonyms') or []):
                if not isinstance(item, dict):
                    continue
                raw.append(item)
                label = indexed.get(item.get('text'), {})
                review_rows.append(dict(clusterLabel=row['clusterLabel'], variant=variant,
                    index=index, question=parsed.get('standardQuestion'), **item,
                    expectedType=label.get('expectedType'), reason=label.get('reason')))
            accepted.extend(row['accepted'])
        def metrics(items):
            scored = [r for r in items if indexed.get(r.get('text'), {}).get('expectedType') in
                      {'TYPO', 'ABBR', 'SYNONYM'}]
            typo = [r for r in scored if r.get('type') == 'TYPO']
            correct_typo = sum(indexed[r['text']]['expectedType'] == 'TYPO' for r in typo)
            return dict(count=len(items), labeled=len(scored), typeCounts=dict(Counter(r.get('type') for r in items)),
                ambiguous=sum(indexed.get(r.get('text'), {}).get('expectedType') == 'REVIEW_REQUIRED'
                              for r in items),
                unreviewed=sum(r.get('text') not in indexed for r in items),
                correctTypes=sum(r.get('type') == indexed[r['text']]['expectedType'] for r in scored),
                typoPredictions=len(typo), correctTypoPredictions=correct_typo,
                falseTypoPredictions=len(typo) - correct_typo,
                typoPrecision=correct_typo / len(typo) if typo else None)
        details = [a for r in selected for a in r['audit'] if (r['parsed'] or {}).get('tag') == 'FAQ']
        true_typos = sum(indexed.get(r.get('text'), {}).get('expectedType') == 'TYPO' for r in raw)
        retained_true_typos = sum(r.get('type') == 'TYPO' and
            indexed.get(r.get('text'), {}).get('expectedType') == 'TYPO' for r in accepted)
        summaries[variant] = dict(generations=len(selected), parsed=sum(isinstance(r['parsed'], dict) for r in selected),
            truncated=sum(r['truncated'] for r in selected),
            faqCandidates=sum((r['parsed'] or {}).get('tag') == 'FAQ' for r in selected),
            raw=metrics(raw), filtered=metrics(accepted),
            held=sum(a['status'] == 'REVIEW_REQUIRED' for a in details),
            dropped=sum(a['status'] == 'DROPPED' for a in details),
            retention=len(accepted) / len(raw) if raw else None,
            trueTyposInProposals=true_typos,
            typoRecallWithinProposals=retained_true_typos / true_typos if true_typos else None)
    summary = dict(scope='Codex-reviewed pilot, not independent human accuracy or production guarantee',
                   variants=summaries,
                   diagnosticControls=dict(count=len(control_results),
                       trueTypos=sum(c['expectedType'] == 'TYPO' for c in control_results),
                       confirmedTypos=sum(bool(c['accepted']) and c['accepted'][0]['type'] == 'TYPO' for c in control_results),
                       held=sum(c['audit'][0]['status'] == 'REVIEW_REQUIRED' for c in control_results)))
    (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    (out / 'review-items.json').write_text(json.dumps(review_rows, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
