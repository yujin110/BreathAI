"""또타24 기존 FAQ 검색기 — BGE-M3 임베딩 + BGE reranker 2단계 검색.

입력:  일반상담 Excel                     (수제작 기존 FAQ)
       counselling_info CSV              (수제작 기존 FAQ)
       dataset/seoulmetro_kb_clean.jsonl  (추가 안내 문서, 선택)
       MariaDB gold_faq                   (운영 승인 FAQ, 매 실행 동기화)
       dataset/faq_candidates.json       (질의 = standardQuestion)
출력:  dataset/kb_embeddings.npy         (KB 임베딩 캐시)
       dataset/faq_retrieval.json        (FAQ별 top-k 검색 결과)

설계:
  - 1차: BGE-M3 dense embedding cosine sim (top_k1=20)
  - 2차: BGE-reranker로 rerank (top_k2=5)
  - 표준질문이 비어있거나 FAQ 태그 아닌 클러스터는 스킵
"""
from __future__ import annotations

import gc
import hashlib
import csv
import json
import os
import time
import unicodedata
from pathlib import Path
from services.runtime_config import runtime_path

import numpy as np
import torch
from sentence_transformers import CrossEncoder, SentenceTransformer

from services.faq_kb import load_current_kb


def _nfd(p: Path) -> Path:
    return Path(unicodedata.normalize('NFD', str(p)))


# 경로
DATA_DIR = runtime_path('TTOBAGI_DATA_DIR', _nfd(Path(__file__).resolve().parent.parent / 'dataset'))
KB_JSONL = DATA_DIR / 'seoulmetro_kb_clean.jsonl'
KB_EMB = DATA_DIR / 'kb_embeddings.npy'
KB_EMB_META = DATA_DIR / 'kb_embeddings.meta.json'
FAQ_JSON = DATA_DIR / 'faq_candidates.json'
CLUSTERS_CSV = DATA_DIR / 'clusters.csv'
OUTPUT_JSON = DATA_DIR / 'faq_retrieval.json'

# 모델
DENSE_MODEL = 'BAAI/bge-m3'
RERANK_MODEL = 'BAAI/bge-reranker-v2-m3'
DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'

TOP_K1 = 20    # dense retrieval
TOP_K2 = 5     # after rerank
MAX_SEQ_LENGTH = 512
BATCH_SIZE = int(os.getenv('TTOBAGI_RETRIEVAL_BATCH_SIZE', '4'))
if BATCH_SIZE < 1:
    raise ValueError('TTOBAGI_RETRIEVAL_BATCH_SIZE must be positive')
FAQ_MATCH_THRESHOLD = float(os.getenv('TTOBAGI_FAQ_MATCH_THRESHOLD', '0.80'))
SIMILAR_QUESTION_LIMIT = int(os.getenv('TTOBAGI_SIMILAR_QUESTION_LIMIT', '5'))
FAQ_SOURCE_TYPES = {'general_consultation', 'counselling_info', 'approved_faq'}


def load_kb() -> list[dict]:
    """
    일반상담/counselling 기존 FAQ, 선택 문서, DB 승인 FAQ를 모두 로드한다.

    운영자가 신규 FAQ를 반영하면 `gold_faq`에 저장되며, 다음 실행부터
    이 함수가 해당 FAQ의 질문·답변·키워드를 검색 KB에 포함한다.
    """
    kb, counts = load_current_kb(KB_JSONL)
    if not kb:
        raise RuntimeError(
            '검색 가능한 KB가 없습니다. seoulmetro_kb_clean.jsonl을 제공하거나 '
            'gold_faq에 활성 FAQ를 등록해야 합니다.'
        )
    print(
        'KB 병합: '
        f'문서={counts["base"]}, 일반상담={counts["generalConsultation"]}, '
        f'counselling={counts["counsellingInfo"]}, 승인 FAQ={counts["approvedFaq"]}, '
        f'최종={counts["merged"]}'
    )
    return kb


def kb_fingerprint(kb: list[dict]) -> str:
    """FAQ 추가·삭제와 질문·답변·키워드 수정을 감지하는 콘텐츠 해시를 만든다."""
    canonical = json.dumps(
        kb,
        ensure_ascii=False,
        sort_keys=True,
        separators=(',', ':'),
        default=str,
    )
    return hashlib.sha256(canonical.encode('utf-8')).hexdigest()


def build_or_load_index(
    kb: list[dict],
    force_rebuild: bool = False,
) -> np.ndarray:
    """
    통합 FAQ의 `text`를 BGE-M3 벡터로 변환하고 .npy에 캐시한다.

    KB 내용, 레코드 수 또는 모델명이 바뀌면 캐시를 재생성한다.
    force_rebuild=True면 변경 여부와 관계없이 다시 계산한다.
    """

    fingerprint = kb_fingerprint(kb)
    meta = None
    if KB_EMB_META.exists():
        try:
            meta = json.loads(KB_EMB_META.read_text(encoding='utf-8'))
        except (json.JSONDecodeError, OSError):
            meta = None

    cache_valid = (
        KB_EMB.exists()
        and meta is not None
        and meta.get('fingerprint') == fingerprint
        and meta.get('model') == DENSE_MODEL
        and meta.get('max_seq_length') == MAX_SEQ_LENGTH
        and meta.get('count') == len(kb)
    )

    if cache_valid and not force_rebuild:
        emb = np.load(KB_EMB)

        if emb.shape[0] == len(kb):
            print(f'KB 임베딩 캐시 hit: {KB_EMB.name} ({emb.shape})')
            return emb

        print('KB 임베딩 캐시 shape 불일치, 재계산')

    elif KB_EMB.exists() and not force_rebuild:
        print('KB 내용 변경 감지, 임베딩 재계산')

    print(f'KB 임베딩 계산 중 (n={len(kb)}, model={DENSE_MODEL})')

    enc = SentenceTransformer(DENSE_MODEL, device=DEVICE)
    enc.max_seq_length = MAX_SEQ_LENGTH
    texts = [c['text'] for c in kb]

    emb = enc.encode(
        texts,
        batch_size=BATCH_SIZE,
        normalize_embeddings=True,
        show_progress_bar=True,
        convert_to_numpy=True,
    )

    del enc
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    np.save(KB_EMB, emb)
    KB_EMB_META.write_text(
        json.dumps(
            {
                'fingerprint': fingerprint,
                'model': DENSE_MODEL,
                'max_seq_length': MAX_SEQ_LENGTH,
                'count': len(kb),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding='utf-8',
    )

    print(f'저장: {KB_EMB.name} ({emb.shape}, {KB_EMB.stat().st_size:,} bytes)')

    return emb


def search(
    query: str,
    enc: SentenceTransformer,
    kb: list[dict],
    kb_emb: np.ndarray,
    top_k: int = TOP_K1,
) -> list[dict]:
    """
    BGE-M3 임베딩의 코사인 유사도로 1차 후보를 검색한다.

    질문과 FAQ 벡터가 정규화되어 있으므로 행렬 내적 `kb_emb @ q_emb`가
    코사인 유사도와 같다. 키워드 매칭 개수를 별도로 세지는 않는다.
    """

    q_emb = enc.encode(
        [query],
        normalize_embeddings=True,
        convert_to_numpy=True,
    )[0]

    sims = kb_emb @ q_emb
    idx = np.argsort(-sims)[:top_k]

    return [
        {
            **kb[i],
            'dense_score': float(sims[i]),
        }
        for i in idx
    ]


def rerank(
    query: str,
    candidates: list[dict],
    reranker: CrossEncoder,
    top_k: int = TOP_K2,
) -> list[dict]:
    """
    1차 후보를 BGE CrossEncoder로 다시 평가해 상위 FAQ를 반환한다.

    입력 쌍은 `(생성된 표준 질문, 기존 FAQ의 질문+답변+키워드 text)`이다.
    sigmoid가 적용된 rerank_score를 최종 NEW/EXPAND 판정에 사용한다.
    """

    if not candidates:
        return []

    pairs = [
        (query, c['text'])
        for c in candidates
    ]

    scores = reranker.predict(
        pairs,
        batch_size=BATCH_SIZE,
        show_progress_bar=False,
    )

    for c, s in zip(candidates, scores):
        c['rerank_score'] = float(s)

    candidates.sort(key=lambda c: -c['rerank_score'])

    return candidates[:top_k]


def load_faq_candidates() -> list[dict]:
    """
    faq_generator.py가 생성한 FAQ 후보 JSON을 로드한다.

    각 후보의 standardQuestion을 기존 FAQ 검색 질의로 사용한다.
    """

    if not FAQ_JSON.exists():
        raise FileNotFoundError(
            f"FAQ 후보 파일을 찾을 수 없습니다: {FAQ_JSON}"
        )

    data = json.loads(FAQ_JSON.read_text(encoding='utf-8'))

    return data.get('faqCandidates', [])


def load_similar_questions() -> dict[int, list[str]]:
    """클러스터별 실제 사용자 질문을 중복 없이 상위 N개 반환한다."""
    if not CLUSTERS_CSV.exists():
        return {}
    grouped: dict[int, list[str]] = {}
    with CLUSTERS_CSV.open(encoding='utf-8-sig', newline='') as fp:
        for row in csv.DictReader(fp):
            cluster_id = int(row['clusterId'])
            text = str(row.get('text') or '').strip()
            questions = grouped.setdefault(cluster_id, [])
            if text and text not in questions and len(questions) < SIMILAR_QUESTION_LIMIT:
                questions.append(text)
    return grouped


def run_for_faqs() -> None:
    """
    FAQ 후보를 기존 FAQ와 비교하고 검색·분류 필드를 보강한다.

    처리 순서:
    1. 네 원천을 통합한 KB 로드 및 임베딩
    2. standardQuestion으로 코사인 유사도 상위 20개 검색
    3. reranker로 상위 5개 재정렬
    4. 최고 점수 결과가 운영 FAQ ID를 가지고 임계값 이상이면 EXPAND, 아니면 NEW
    5. qType/category/matchedFaqs/similarQuestions를 후보에 기록
    """

    kb = load_kb()
    print(f'KB chunks: {len(kb)}')

    kb_emb = build_or_load_index(kb)

    enc = SentenceTransformer(DENSE_MODEL, device=DEVICE)
    enc.max_seq_length = MAX_SEQ_LENGTH

    reranker = CrossEncoder(
        RERANK_MODEL,
        max_length=512,
        device=DEVICE,
        activation_fn=torch.nn.Sigmoid(),
    )

    print(f'reranker loaded: {RERANK_MODEL}')

    candidates = load_faq_candidates()
    similar_by_cluster = load_similar_questions()

    faqs = [
        c for c in candidates
        if c.get('tag') == 'FAQ' and c.get('standardQuestion')
    ]

    print(f'\n질의 (FAQ standardQuestion): {len(faqs)}개\n')

    results = []

    for i, c in enumerate(faqs, 1):
        q = c['standardQuestion']
        t0 = time.time()

        dense_candidates = search(
            query=q,
            enc=enc,
            kb=kb,
            kb_emb=kb_emb,
            top_k=TOP_K1,
        )

        top = rerank(
            query=q,
            candidates=dense_candidates,
            reranker=reranker,
            top_k=TOP_K2,
        )

        elapsed = time.time() - t0

        record = {
            'clusterLabel': c['clusterLabel'],
            'clusterSize': c['clusterSize'],
            'query': q,
            'retrieved': [
                {
                    'source_type': t.get('source_type', 'base_kb'),
                    'source_url': t.get('source_url'),
                    'menuIdx': t.get('menuIdx'),
                    'page_label': t.get('page_label'),
                    'chunk_id': t.get('chunk_id'),
                    'faq_id': t.get('faq_id'),
                    'source_seq_num': t.get('source_seq_num'),
                    'q_type': t.get('q_type'),
                    'category': t.get('category'),
                    'question': t.get('question'),
                    'keywords': t.get('keywords', []),
                    'text': t.get('text'),
                    'dense_score': round(t.get('dense_score', 0.0), 4),
                    'rerank_score': round(t.get('rerank_score', 0.0), 4),
                }
                for t in top
            ],
        }

        # 최고 점수 FAQ를 운영 DB에서 찾을 수 있을 때만 EXPAND로 판정한다.
        best_match = (
            top[0]
            if top
            and top[0].get('source_type') in FAQ_SOURCE_TYPES
            and top[0].get('faq_id') is not None
            and top[0].get('rerank_score', 0.0) >= FAQ_MATCH_THRESHOLD
            else None
        )
        matched = [
            item for item in top
            if item.get('source_type') in FAQ_SOURCE_TYPES
            and item.get('rerank_score', 0.0) >= FAQ_MATCH_THRESHOLD
        ]
        record.update({
            'candidateType': 'EXPAND' if best_match else 'NEW',
            'qType': best_match.get('q_type') if best_match else None,
            'category': best_match.get('category') if best_match else None,
            'similarQuestions': c.get('similarQuestions') or similar_by_cluster.get(c['clusterLabel'], []),
            'matchedFaqs': [
                {
                    'matchedFaqId': item.get('faq_id'),
                    'matchedFaqSeqNum': item.get('source_seq_num'),
                    'matchScore': round(item.get('rerank_score', 0.0), 4),
                }
                for item in matched
            ],
        })
        # 후속 답변 생성·백엔드 payload 단계가 같은 파일을 읽을 수 있도록
        # 원본 faqCandidates에도 새 API 계약 필드를 반영한다.
        c.update({
            'candidateType': record['candidateType'],
            'qType': record['qType'],
            'category': record['category'],
            'similarQuestions': record['similarQuestions'],
            'matchedFaqs': record['matchedFaqs'],
        })
        results.append(record)

        display_best = top[0] if top else None

        if display_best:
            print(
                f'  [{i:>2}/{len(faqs)}] C{c["clusterLabel"]:>2}  '
                f'top={display_best["rerank_score"]:.3f}  '
                f'q="{q[:36]}" → "{str(display_best.get("page_label", ""))[:20]}"  '
                f'({elapsed:.2f}s)'
            )
        else:
            print(
                f'  [{i:>2}/{len(faqs)}] C{c["clusterLabel"]:>2}  no result'
            )

    FAQ_JSON.write_text(
        json.dumps({'faqCandidates': candidates}, ensure_ascii=False, indent=2),
        encoding='utf-8',
    )
    OUTPUT_JSON.write_text(
        json.dumps(
            {
                'faqRetrieval': results,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding='utf-8',
    )

    print(
        f'\n산출물: {OUTPUT_JSON.name} '
        f'({OUTPUT_JSON.stat().st_size:,} bytes, {len(results)}건)'
    )


def main() -> None:
    """
    run_pipeline.py에서 호출하는 retriever 단계 진입점.

    services/run_pipeline.py의 run_full_pipeline()은 RET.main()을 호출하므로,
    이 함수가 반드시 존재해야 한다.
    """

    run_for_faqs()


if __name__ == '__main__':
    main()
