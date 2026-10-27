"""Conservative synonym type validation; no model or third-party imports.

SYNONYM remains a model-proposed intent relation, not a proof of intent equality.
Only explicit policy entries can turn a model's TYPO into a confirmed type.
"""
import csv
import hashlib
import json
from pathlib import Path
import re
import unicodedata

POLICY_PATH = Path(__file__).resolve().parents[1] / 'dataset/synonym_policy/v1.json'


def normalize(text):
    return unicodedata.normalize('NFC', text).strip()


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode('utf-8')).hexdigest()


def load_policy():
    return json.loads(POLICY_PATH.read_text(encoding='utf-8'))


def validate_synonyms(items, sources=(), *, policy=None):
    policy = load_policy() if policy is None else policy
    observed = {}
    for row in sources:
        observed.setdefault(normalize(row['text']), []).append(row['logId'])
    accepted, records, seen = [], [], set()
    if not isinstance(items, list):
        items = [items]
    for index, item in enumerate(items):
        text = item.get('text') if isinstance(item, dict) else None
        proposed = item.get('type') if isinstance(item, dict) else None
        record = dict(index=index, candidateText=text, proposedType=proposed,
                      finalType=None, status='REVIEW_REQUIRED', reasonCode='INVALID_INPUT',
                      correctedText=None, errorSpan=None, expandedText=None,
                      ruleId=None, sourceKind='GENERATED', sourceLogIds=[])
        records.append(record)
        if not isinstance(text, str) or not normalize(text) or len(text) > 100:
            record.update(status='DROPPED')
            continue
        key = normalize(text)
        ids = observed.get(key, [])
        record.update(sourceKind='OBSERVED' if ids else 'GENERATED', sourceLogIds=ids)
        if not isinstance(proposed, str) or proposed not in {'TYPO', 'ABBR', 'SYNONYM'}:
            record['reasonCode'] = 'INVALID_TYPE'
            continue
        # Entire-expression allowlist: never infer normality from a substring.
        normal = policy['normalExpressions'].get(key)
        abbreviations = [(a, b) for a, b in policy['abbreviations'].items()
                         if re.search(r'(?<![가-힣A-Za-z0-9])' + re.escape(a)
                                      + r'(?![가-힣A-Za-z0-9])', key)]
        typo = policy['observedTypos'].get(key)
        final = None
        if typo and ids:
            final = 'TYPO'
            start, end = typo['errorSpan']
            if not (0 <= start < end <= len(key)) or normalize(typo['correctedText']) == key:
                raise ValueError('Invalid synonym typo policy')
            record.update(correctedText=typo['correctedText'], errorSpan=[start, end],
                          ruleId='observed-typo', reasonCode='OBSERVED_APPROVED_TYPO')
        elif typo:
            record['reasonCode'] = 'TYPO_NOT_OBSERVED'
        elif normal:
            final = normal
            record.update(ruleId='normal-expression', reasonCode='APPROVED_NORMAL_EXPRESSION')
        elif proposed == 'TYPO':
            # A known abbreviation does not prove the rest of the text is correct.
            record['reasonCode'] = 'UNVERIFIED_TYPO'
        elif abbreviations:
            final = 'ABBR'
            record.update(expandedText=key, ruleId='abbreviation', reasonCode='APPROVED_ABBREVIATION')
            for short, expanded in abbreviations:
                record['expandedText'] = record['expandedText'].replace(short, expanded)
        elif proposed == 'ABBR':
            record['reasonCode'] = 'UNVERIFIED_ABBREVIATION'
        else:
            final = 'SYNONYM'
            record['reasonCode'] = 'MODEL_PROPOSED_SYNONYM'
        if final is None:
            continue
        if final == 'ABBR':
            record['expandedText'] = key
            for short, expanded in abbreviations:
                record['expandedText'] = record['expandedText'].replace(short, expanded)
        if key in seen or len(accepted) >= 10:
            record.update(status='DROPPED', reasonCode='DUPLICATE_OR_LIMIT')
            continue
        seen.add(key)
        record.update(finalType=final, status='ACCEPTED' if final == proposed else 'RECLASSIFIED')
        accepted.append({'text': text, 'type': final})
    return accepted, records


def finalize_candidates(candidates, data, *, report_path=None):
    """Validate after all question refinement; write a content-bound audit."""
    data = Path(data)
    policy = load_policy()
    sources = {}
    with (data / 'clusters.csv').open(encoding='utf-8-sig', newline='') as stream:
        for row in csv.DictReader(stream):
            sources.setdefault(int(row['clusterId']), []).append(row)
    records = []
    for index, candidate in enumerate(candidates):
        rows = sources.get(candidate['clusterLabel'], [])
        proposals = candidate.pop('_synonymProposals', candidate.get('synonyms', []))
        accepted, details = validate_synonyms(proposals, rows, policy=policy)
        candidate['synonyms'] = accepted
        records.append(dict(candidateIndex=index, clusterLabel=candidate['clusterLabel'], records=details))
    audit = dict(policyVersion=policy['version'], policySha256=digest(policy),
                 candidatesSha256=digest(candidates), candidates=records)
    target = Path(report_path) if report_path is not None else data / 'synonym_validation.json'
    target.write_text(
        json.dumps(audit, ensure_ascii=False, indent=2), encoding='utf-8')
    return audit


def verify_audit(candidates, data):
    path = Path(data) / 'synonym_validation.json'
    if not path.exists():
        raise ValueError('Synonym validation artifact missing; rerun the pipeline')
    audit = json.loads(path.read_text(encoding='utf-8'))
    policy = load_policy()
    if (audit.get('policyVersion') != policy['version']
            or audit.get('policySha256') != digest(policy)
            or audit.get('candidatesSha256') != digest(candidates)):
        raise ValueError('Synonym validation artifact does not match final candidates or policy')


def main():
    """Read-only review of older results; never rewrites candidates or database."""
    import argparse
    parser = argparse.ArgumentParser(description=main.__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    # Refuse overwriting existing artifacts, including the source result.
    if args.report.exists():
        parser.error('--report must be a new file')
    candidates = json.loads((args.data / 'faq_candidates_final.json').read_text(
        encoding='utf-8'))['faqCandidates']
    audit = finalize_candidates(candidates, args.data, report_path=args.report)
    count = sum(r['status'] == 'REVIEW_REQUIRED' for c in audit['candidates'] for r in c['records'])
    print(f'Review required: {count}; report: {args.report}')


if __name__ == '__main__':
    main()
