"""Model-free validation of the retriever -> answer pipeline boundary."""
from __future__ import annotations

import copy
import math


MATCH_FIELDS = ('candidateType', 'qType', 'category', 'similarQuestions', 'matchedFaqs',
                'matchStatus', 'matchDecisionReason', 'matchEvidence')


def index_retrieval(payload: dict) -> dict[int, dict]:
    records = payload.get('faqRetrieval')
    if not isinstance(records, list):
        raise ValueError('faqRetrieval must be a list')
    indexed = {}
    for record in records:
        cid = record.get('clusterLabel')
        if type(cid) is not int or cid in indexed:
            raise ValueError(f'Invalid or duplicate retrieval clusterLabel: {cid}')
        if not isinstance(record.get('query'), str) or not record['query'].strip():
            raise ValueError(f'C{cid}: retrieval query is missing')
        docs = record.get('retrieved')
        if not isinstance(docs, list) or len(docs) > 5:
            raise ValueError(f'C{cid}: expected up to 5 retrieved documents')
        for doc in docs:
            score = doc.get('rerank_score')
            if (isinstance(score, bool) or not isinstance(score, (int, float))
                    or not math.isfinite(score) or not 0 <= score <= 1):
                raise ValueError(f'C{cid}: invalid sigmoid rerank_score')
            if not isinstance(doc.get('text'), str) or not doc['text'].strip():
                raise ValueError(f'C{cid}: retrieved text is missing')
        indexed[cid] = copy.deepcopy(record)
    return indexed


def bind_retrieval(candidates: list[dict], retrieval: dict[int, dict]) -> list[dict]:
    """Reject missing/stale FAQ retrieval before loading models; preserve metadata."""
    result, expected, seen = [], set(), set()
    for source in candidates:
        candidate = copy.deepcopy(source)
        cid = candidate.get('clusterLabel')
        if type(cid) is not int or cid in seen:
            raise ValueError(f'Invalid or duplicate candidate clusterLabel: {cid}')
        seen.add(cid)
        if candidate.get('tag') == 'FAQ':
            question = (candidate.get('standardQuestion') or '').strip()
            if not question:
                raise ValueError(f'C{cid}: FAQ standardQuestion is missing')
            expected.add(cid)
            record = retrieval.get(cid)
            if record is None or record['query'].strip() != question:
                raise ValueError(f'C{cid}: missing/stale retrieval; rerun services.retriever')
            for field in MATCH_FIELDS:
                if field in record:
                    candidate[field] = copy.deepcopy(record[field])
        result.append(candidate)
    if set(retrieval) != expected:
        raise ValueError('Retrieval contains clusters outside the current FAQ candidate set')
    return result


def select_evidence(record: dict | None, threshold: float, limit: int) -> list[dict]:
    if record is None:
        return []
    return copy.deepcopy(sorted(
        (doc for doc in record['retrieved'] if doc['rerank_score'] >= threshold),
        key=lambda doc: -doc['rerank_score'],
    )[:limit])


def evidence_label(doc: dict) -> str:
    return str(doc.get('page_label') or doc.get('chunk_id') or doc.get('question') or 'FAQ')
