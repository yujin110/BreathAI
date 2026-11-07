"""Translate real pipeline artifacts to the existing BE callback DTO."""
import csv
import json
from pathlib import Path
from services.dashboard_metrics import build_dashboard
from services.keyword_policy import KeywordPolicy, get_policy


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
    clusters = []
    for label, row in summaries.items():
        words = keywords.get(str(label), [])
        clusters.append({"clusterLabel": label, "name": '노이즈' if label == -1 else (", ".join(words[:3]) or f"클러스터 {label}"),
                         "topKeywords": words, "size": int(row["size"])})
    candidates = []
    with (data / 'clusters.csv').open(encoding='utf-8-sig', newline='') as stream:
        cluster_logs = {}
        for row in csv.DictReader(stream):
            cluster_logs.setdefault(int(row['clusterId']), set()).add(int(row['logId']))
    for candidate in source["faqCandidates"]:
        label = candidate["clusterLabel"]
        if label not in summaries or label == -1:
            raise ValueError("Candidate refers to unknown cluster")
        if 'finalClusterIds' in candidate:
            labels = candidate['finalClusterIds']
            if not labels or label not in labels or any(cid == -1 or cid not in summaries for cid in labels):
                raise ValueError('Candidate cluster provenance is invalid')
            expected_logs = set().union(*(cluster_logs.get(cid, set()) for cid in labels))
            if set(candidate.get('logIds', [])) != expected_logs:
                raise ValueError('Candidate log provenance differs from its final clusters')
        kind = candidate.get("candidateType", "NEW")
        if kind not in {"NEW", "EXPAND"}:
            raise ValueError("Invalid candidateType")
        synonyms = candidate.get("synonyms") or []
        for synonym in synonyms:
            if not isinstance(synonym.get("text"), str) or synonym.get("type") not in {"TYPO", "ABBR", "SYNONYM"}:
                raise ValueError("Invalid synonym")
        mapped = {key: candidate.get(key) for key in (
            "standardQuestion", "answerDraft", "qType", "category")}
        mapped.update({
            "clusterLabel": label, "candidateType": kind,
            "occurrenceCount": len(set(candidate['logIds'])) if 'logIds' in candidate else int(summaries[label]["size"]),
            "representativeKeywords": candidate.get("topKeywords") or keywords.get(str(label), []),
            "similarQuestions": candidate.get("similarQuestions") or [],
            "synonyms": synonyms, "matchedFaqs": candidate.get("matchedFaqs") or [],
        })
        if 'finalClusterIds' in candidate or 'matchStatus' in candidate:
            mapped['analysisProvenance'] = {key: candidate.get(key) for key in (
                'candidateId', 'sourceCandidateIds', 'finalClusterIds', 'logIds',
                'matchStatus', 'matchDecisionReason', 'matchEvidence')}
        if not all(isinstance(mapped[key], str) for key in ("standardQuestion", "answerDraft")):
            raise ValueError("Question and answer must be strings")
        candidates.append(mapped)
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
