"""Translate real pipeline artifacts to the existing BE callback DTO."""
import csv
import json
import re
from pathlib import Path
from services.dashboard_metrics import build_dashboard
from services.faq_tag_quality import clean_keywords, clean_tag
from services.grounded_answer import is_insufficient_answer
from services.keyword_policy import KeywordPolicy, get_policy, normalize


_QUESTION_WORD_END = re.compile(r'(?:가요|나요|까요|습니까|는지|인지요|은지요|해요|입니다)$')
_GENERIC_QUESTION_TERMS = {'방법', '방법은', '절차', '절차는', '안내', '안내는'}


def build_callback(job_dir):
    data = Path(job_dir) / "dataset"
    snapshot = Path(job_dir) / 'keyword_policy.json'
    if snapshot.exists():
        policy = KeywordPolicy.load(snapshot)
        audit = json.loads((data / 'keyword_policy_audit.json').read_text(encoding='utf-8'))
        if (audit.get('policyVersion'), audit.get('policySha256')) != (policy.version, policy.sha256):
            raise ValueError('Keyword policy audit does not match job snapshot')
    else:
        if (data / 'keyword_policy_audit.json').exists():
            raise ValueError('Keyword policy snapshot is missing for audited job')
        # Compatibility for artifacts produced before keyword policies existed.
        policy = get_policy()
    source = json.loads((data / "faq_candidates_final.json").read_text(encoding="utf-8"))
    if not isinstance(source, dict) or not isinstance(source.get("faqCandidates"), list):
        raise ValueError("Invalid final result")
    keywords = json.loads((data / "cluster_keywords.json").read_text(encoding="utf-8"))
    with (data / "cluster_summary.csv").open(encoding="utf-8-sig", newline="") as file:
        summaries = {int(row["clusterId"]): row for row in csv.DictReader(file)}
    cluster_keywords = {label: clean_keywords(keywords.get(str(label), []), policy=policy)
                        for label in summaries}
    candidate_keywords = {}
    candidates = []
    with (data / 'clusters.csv').open(encoding='utf-8-sig', newline='') as stream:
        cluster_logs = {}
        for row in csv.DictReader(stream):
            cluster_logs.setdefault(int(row['clusterId']), set()).add(int(row['logId']))
    for candidate in source["faqCandidates"]:
        label = candidate["clusterLabel"]
        if label not in summaries or label == -1:
            raise ValueError("Candidate refers to unknown cluster")
        labels = candidate.get('finalClusterIds', [label])
        if 'finalClusterIds' in candidate:
            if not labels or label not in labels or any(cid == -1 or cid not in summaries for cid in labels):
                raise ValueError('Candidate cluster provenance is invalid')
            expected_logs = set().union(*(cluster_logs.get(cid, set()) for cid in labels))
            if set(candidate.get('logIds', [])) != expected_logs:
                raise ValueError('Candidate log provenance differs from its final clusters')
        kind = candidate.get("candidateType", "NEW")
        if kind not in {"NEW", "EXPAND"}:
            raise ValueError("Invalid candidateType")
        synonyms = []
        seen = set()
        for synonym in candidate.get("synonyms") or []:
            if (not isinstance(synonym, dict) or not isinstance(synonym.get("text"), str)
                    or synonym.get("type") not in ("TYPO", "ABBR", "SYNONYM")):
                raise ValueError("Invalid synonym")
            text = clean_tag(synonym["text"], policy=policy)
            if text is not None and normalize(text) not in seen:
                synonyms.append({"text": text, "type": synonym["type"]})
                seen.add(normalize(text))
        mapped = {key: candidate.get(key) for key in (
            "standardQuestion", "answerDraft", "qType", "category")}
        if not all(isinstance(mapped[key], str) for key in ("standardQuestion", "answerDraft")):
            raise ValueError("Question and answer must be strings")
        if not mapped['standardQuestion'].strip() or is_insufficient_answer(mapped['answerDraft']):
            continue
        words = clean_keywords(candidate.get('topKeywords') or [], policy=policy)
        if not words:
            words = clean_keywords([word for cid in labels for word in cluster_keywords[cid]], policy=policy)
        if not words and kind == 'EXPAND':
            words = clean_keywords(candidate.get('approvedFaqKeywords') or [], policy=policy)
        if not words:
            terms, _ = policy.tokenize(mapped['standardQuestion'])
            words = clean_keywords([term for term in terms if term not in _GENERIC_QUESTION_TERMS
                                    and not _QUESTION_WORD_END.search(term)],
                                   policy=policy)
        words = words[:5]
        for cid in labels:
            candidate_keywords.setdefault(cid, []).extend(words)
        mapped.update({
            "clusterLabel": label, "candidateType": kind,
            "occurrenceCount": len(set(candidate['logIds'])) if 'logIds' in candidate else int(summaries[label]["size"]),
            "representativeKeywords": words,
            "similarQuestions": candidate.get("similarQuestions") or [],
            "synonyms": synonyms, "matchedFaqs": candidate.get("matchedFaqs") or [],
        })
        if 'finalClusterIds' in candidate or 'matchStatus' in candidate:
            mapped['analysisProvenance'] = {key: candidate.get(key) for key in (
                'candidateId', 'sourceCandidateIds', 'finalClusterIds', 'logIds',
                'matchStatus', 'matchDecisionReason', 'matchEvidence')}
        candidates.append(mapped)
    clusters = []
    for label, row in summaries.items():
        words = cluster_keywords[label] or clean_keywords(candidate_keywords.get(label, []), policy=policy)
        name_words = []
        for word in words[:3]:
            if len(', '.join([*name_words, word])) > 100:
                break
            name_words.append(word)
        name = '노이즈' if label == -1 else ', '.join(name_words) or '대표 키워드 없음'
        clusters.append({'clusterLabel': label, 'name': name, 'topKeywords': words, 'size': int(row['size'])})
    dashboard = build_dashboard(data, keywords, summaries, policy=policy)
    points = dashboard.pop('points')
    extra = {}
    if (data / 'analysis_metadata.json').exists():
        from services.analysis_selection import finalize_selection
        metadata = json.loads((data / 'analysis_metadata.json').read_text(encoding='utf-8'))
        saved = json.loads((data / 'analysis_scope.json').read_text(encoding='utf-8'))
        extra = finalize_selection(data, min_cluster_size=metadata['minClusterSize'],
                                   min_samples=metadata['minSamples'], faq_min_size=metadata['faqMinSize'])
        if extra != saved:
            raise ValueError('Analysis scope differs from actual artifacts')
        scope = extra['analysisScope']
        counts = dashboard['systemStatus']['samplingStatus']
        if (scope['totalLogCount'] != dashboard['systemStatus']['totalLogCount'] or
                scope['totalLogCount'] != sum(counts.values()) or
                scope['totalLogCount'] != sum(row['totalCnt'] for row in dashboard['usageStats']) or
                scope['visualizedLogCount'] != len(points)):
            raise ValueError('Analysis aggregate mismatch')
    return {"status": "COMPLETED", "faqCandidates": candidates, **dashboard, **extra,
            "clusteringView": {"clusterNames": clusters, "points": points}}
