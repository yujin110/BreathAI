"""또타24 답변 파이프라인 통합 실행기 — 단일 패스, 모델 1회 로드.

실행:
  python -m services.run_pipeline           # 답변 단계만 (regen → eval → needsReview면 refine → OOS+corp이면 case-guide)
  python -m services.run_pipeline --full    # preprocess부터 전체

설계:
  - 기존 4개 모듈(answer_regenerator/answer_evaluator/answer_refiner/oos_refresher)의
    핵심 함수만 import. 각 모듈은 단독 실행/디버깅도 계속 가능.
  - LLM/CE를 한 번만 로드해서 전 단계에서 공유.
  - 81 클러스터 단일 루프: 매 클러스터마다 regen→eval, needsReview면 refine→재eval,
    OUT_OF_SCOPE+corporate_info면 case-guide로 다시 시도 (좋아질 때만 채택).
  - 최종 산출 하나: dataset/faq_candidates_final.json
"""
from __future__ import annotations

import argparse
import copy
import json
import time
import unicodedata
from pathlib import Path
from services.runtime_config import runtime_path
from services.keyword_policy import get_policy

import numpy as np
import pandas as pd

from services.retrieval_contract import bind_retrieval
from services.analysis_progress import report as progress, Performance, measure, count


def _nfd(p):
    return Path(unicodedata.normalize('NFD', str(p)))


DATA_DIR = runtime_path('TTOBAGI_DATA_DIR', _nfd(Path(__file__).resolve().parent.parent / 'dataset'))
LOG_DIR = runtime_path('TTOBAGI_LOG_DIR', _nfd(Path(__file__).resolve().parent.parent / 'logs'))

OUTPUT_JSON = DATA_DIR / 'faq_candidates_final.json'
CLUSTERS_CSV = DATA_DIR / 'clusters.csv'
RUN_LOG = LOG_DIR / 'run_pipeline.log'

N_ATTEMPTS = 3


# ---------------------------------------------------------------------------
# 로그
# ---------------------------------------------------------------------------
def _ensure_logs() -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    RUN_LOG.write_text('', encoding='utf-8')


def _log(msg: str) -> None:
    print(msg, flush=True)
    with RUN_LOG.open('a', encoding='utf-8') as f:
        f.write(msg + '\n')


# ---------------------------------------------------------------------------
# 평가 helper
# ---------------------------------------------------------------------------
def _evaluate(cluster: dict, ce, tok, mdl) -> dict:
    import services.answer_evaluator as AE
    q = cluster.get('standardQuestion', '').strip()
    a = cluster.get('answerDraft', '').strip()
    e2 = AE.e2_score(ce, q, a) if q and a else None
    judge, _raw = AE.llm_judge(tok, mdl, cluster)
    llm_mean = (
        round(sum(judge[k] for k in ('accuracy', 'politeness',
                                     'actionability', 'essential_info')) / 4.0, 2)
        if judge else None
    )
    needs, reasons = AE.decide_review(cluster['tag'], e2, judge)
    return {
        'evaluator': 'pipeline (E2+E3)',
        'e2_pair_score': e2,
        'llm_axes': judge,
        'llm_mean': llm_mean,
        'needs_review_v2': needs,
        'review_reasons': reasons,
    }


# ---------------------------------------------------------------------------
# 단계별 함수 (각 단계의 핵심 로직 — 기존 모듈 helpers 이용)
# ---------------------------------------------------------------------------
def stage_regenerate(cluster: dict, retrieval: dict, templates: dict,
                     tok, mdl) -> tuple[str, list, int]:
    """초기 답변 best-of-N 재생성."""
    import services.answer_regenerator as AR
    tag = cluster['tag']
    tag_cfg = templates[tag]
    kb_chunks = AR.select_kb_chunks(retrieval.get(cluster['clusterLabel']))
    prompt = AR.build_prompt(cluster, tag_cfg, templates['_shared'], kb_chunks)

    attempts = []
    for _ in range(N_ATTEMPTS):
        raw = AR.generate(prompt, tok, mdl)
        body = AR.clean_body(raw)
        _ok, missing = AR.validate_answer(body, tag)
        attempts.append((body, missing))
        if not missing:
            break
    best_body, best_missing = min(attempts, key=lambda x: (len(x[1]), -len(x[0])))
    return best_body, best_missing, len(attempts)


def stage_refine(cluster: dict, retrieval: dict, templates: dict,
                 tok, mdl) -> tuple[str, str, bool, int]:
    """needsReview 클러스터: 표준질문(한자 시) + 답변을 피드백 프롬프트로 재생성."""
    import services.answer_regenerator as AR
    import services.answer_refiner as ARF
    prev_q = cluster.get('standardQuestion', '').strip()
    prev_a = cluster.get('answerDraft', '').strip()
    prev_eval = cluster['evaluation']

    new_q = prev_q
    q_refined = False
    if ARF.has_foreign(prev_q):
        new_q = ARF.refine_question(tok, mdl, prev_q,
                                    cluster.get('synonyms', []),
                                    cluster.get('topKeywords', []))
        q_refined = True

    cluster_for_gen = {**cluster, 'standardQuestion': new_q}
    tag_cfg = templates[cluster['tag']]
    kb_chunks = AR.select_kb_chunks(retrieval.get(cluster['clusterLabel']))
    prompt = ARF.build_refine_prompt(
        cluster_for_gen, tag_cfg, templates['_shared'], kb_chunks,
        prev_a, prev_eval.get('llm_axes') or {}, prev_eval.get('review_reasons', []),
    )

    attempts = []
    for _ in range(N_ATTEMPTS):
        raw = AR.generate(prompt, tok, mdl)
        body = AR.clean_body(raw)
        _ok, missing = AR.validate_answer(body, cluster['tag'])
        attempts.append((body, missing))
        if not missing:
            break
    best_body, _ = min(attempts, key=lambda x: (len(x[1]), -len(x[0])))
    return new_q, best_body, q_refined, len(attempts)


def stage_oos_case(cluster: dict, retrieval: dict, templates: dict,
                   clu_df: pd.DataFrame, tok, mdl
                   ) -> tuple[str, str, int] | None:
    """OUT_OF_SCOPE + corporate_info 케이스만 case-guide 단일 가이드로 재생성.
    그 외 케이스(ambiguous/complaint/external)는 자동 개선 효과 없어 skip."""
    if cluster['tag'] != 'OUT_OF_SCOPE':
        return None
    import services.answer_regenerator as AR
    import services.oos_refresher as OOS
    cid = cluster['clusterLabel']
    samples = clu_df[clu_df['clusterId'] == cid]['text'].head(10).tolist()
    case, _hits = OOS.classify_oos(cluster.get('topKeywords', [])[:8], samples)
    if case != 'corporate_info':
        return None

    cluster_for_gen = {**cluster, 'oos_case': case}
    tag_cfg = templates['OUT_OF_SCOPE']
    kb_chunks = AR.select_kb_chunks(retrieval.get(cid))
    prompt = AR.build_prompt(cluster_for_gen, tag_cfg, templates['_shared'], kb_chunks)

    forbidden = tag_cfg['cases'][case].get('forbidden_mentions', [])
    attempts = []
    for _ in range(N_ATTEMPTS):
        raw = AR.generate(prompt, tok, mdl)
        body = AR.clean_body(raw)
        _ok, missing = AR.validate_answer(body, 'OUT_OF_SCOPE')
        for fb in forbidden:
            if fb in body:
                missing.append(f'금지단어 포함: {fb}')
        attempts.append((body, missing))
        if not missing:
            break
    best_body, _ = min(attempts, key=lambda x: (len(x[1]), -len(x[0])))
    return case, best_body, len(attempts)


# ---------------------------------------------------------------------------
# 메인 — 답변 파이프라인 (steps 5-8 통합)
# ---------------------------------------------------------------------------
def run_answer_pipeline(expansions=()) -> None:
    import services.answer_regenerator as AR
    import services.answer_evaluator as AE
    _ensure_logs()
    _log(f'pipeline 시작 — device={AE.DEVICE}')

    candidates = AR.load_candidates()
    progress('ANSWER_PIPELINE', 0, len(candidates), 'CLUSTER')
    retrieval = AR.load_retrieval()
    candidates = bind_retrieval(candidates, retrieval)
    if not candidates:
        from services.analysis_selection import write_json
        write_json(OUTPUT_JSON, {'faqCandidates': list(expansions)})
        return
    templates = AR.load_templates()
    AR.templates_meta = templates['_meta']
    clu_df = pd.read_csv(CLUSTERS_CSV, encoding='utf-8-sig')
    _log(f'대상 {len(candidates)}건')

    from services.llm_runtime import load_pipeline_models
    with measure('ANSWER_MODELS_LOAD'):
        tok, mdl, judge_tok, judge_mdl = load_pipeline_models(
            lambda: AR.load_model(), AE.load_llm)
        ce = AE.load_ce()

    t0 = time.time()
    results = []
    n_refined = 0
    n_oos_corp_adopted = 0

    for i, src in enumerate(candidates, 1):
        c = copy.deepcopy(src)
        cid = c['clusterLabel']
        tag = c['tag']
        history = []
        record = retrieval.get(cid)
        # Retain the original search query even when refinement rewrites the question.
        c['retrievalEvidence'] = {
            'query': record['query'] if record else None,
            'retrieved': copy.deepcopy(record['retrieved']) if record else [],
            'used': AR.select_kb_chunks(record),
            'scoreThreshold': AR.KB_SCORE_THRESHOLD,
            'maxChunks': AR.MAX_KB_CHUNKS,
        }

        # --- Step 5: 초기 답변 재생성 ---
        body, missing, attempts = stage_regenerate(c, retrieval, templates, tok, mdl)
        count('answerGenerationAttempts', attempts)
        c['answerDraft'] = body
        c.setdefault('answerMeta', {}).update({
            'regenerator': 'pipeline v0.1',
            'attempts': attempts,
            'validationMissing': missing,
        })
        history.append('regen')

        # --- Step 6: 평가 ---
        c['evaluation'] = _evaluate(c, ce, judge_tok, judge_mdl)

        # --- Step 7: needsReview면 refine ---
        if c['evaluation']['needs_review_v2']:
            n_refined += 1
            prev = {'standardQuestion': c.get('standardQuestion'),
                    'answerDraft': c['answerDraft'],
                    'evaluation': c['evaluation']}
            new_q, new_body, q_refined, ref_attempts = stage_refine(
                c, retrieval, templates, tok, mdl)
            count('refinedCandidates')
            count('refineAttempts', ref_attempts)
            c['standardQuestion'] = new_q
            c['answerDraft'] = new_body
            c['evaluation'] = _evaluate(c, ce, judge_tok, judge_mdl)
            c['refinement'] = {
                'stage': 'refine',
                'question_refined': q_refined,
                'prev': prev,
                'attempts': ref_attempts,
            }
            history.append('refine')

        # --- Step 8: OUT_OF_SCOPE + corporate_info면 case-guide 시도 ---
        oos_out = stage_oos_case(c, retrieval, templates, clu_df, tok, mdl)
        if oos_out is not None:
            case, trial_body, oos_attempts = oos_out
            count('oosAttempts', oos_attempts)
            # 새 답변 평가 후 더 좋을 때만 채택
            prev_eval = c['evaluation']
            trial = {**c, 'answerDraft': trial_body, 'oos_case': case}
            trial_eval = _evaluate(trial, ce, judge_tok, judge_mdl)
            prev_mean = prev_eval.get('llm_mean') or 0
            trial_mean = trial_eval.get('llm_mean') or 0
            if trial_mean >= prev_mean:
                c.setdefault('refinement', {})
                c['refinement']['oos_case_adopted'] = True
                c['refinement']['oos_case'] = case
                c['refinement']['oos_attempts'] = oos_attempts
                c['answerDraft'] = trial_body
                c['oos_case'] = case
                c['evaluation'] = trial_eval
                c['answerMeta']['oosCase'] = case
                history.append(f'oos_corp({case})')
                n_oos_corp_adopted += 1
            else:
                history.append(f'oos_corp_rejected({case},{trial_mean}<{prev_mean})')

        c['retrievalEvidence']['questionChangedAfterRetrieval'] = bool(
            record and record['query'].strip() != (c.get('standardQuestion') or '').strip()
        )
        if c['retrievalEvidence']['questionChangedAfterRetrieval']:
            # A rewritten question has not been verified against the operating FAQ.
            c.update(candidateType='NEW', matchedFaqs=[], matchStatus='REVIEW_REQUIRED',
                     matchDecisionReason='매칭 후 표준 질문이 변경되어 운영 FAQ를 다시 확인해야 합니다.')
        results.append(c)
        progress('ANSWER_PIPELINE', i, len(candidates), 'CLUSTER')
        ev = c['evaluation']
        flag = 'ok' if not ev['needs_review_v2'] else '!!'
        _log(f'  [{i:>2}/{len(candidates)}] C{cid:>2} [{tag:<13}] '
             f'stages={">".join(history):<32} LLM={ev["llm_mean"]} {flag}')

    elapsed = time.time() - t0
    _log('')
    _log(f'완료 — 소요 {elapsed:.1f}s')

    nr = sum(1 for r in results if r['evaluation'].get('needs_review_v2'))
    means = [r['evaluation']['llm_mean'] for r in results
             if r['evaluation'].get('llm_mean') is not None]
    _log(f'  needsReview     = {nr}/{len(results)}건')
    _log(f'  LLM mean        = {np.mean(means):.2f}')
    _log(f'  refine 발동     = {n_refined}건')
    _log(f'  oos_corp 채택   = {n_oos_corp_adopted}건')

    OUTPUT_JSON.write_text(
        json.dumps({'faqCandidates': [*expansions, *results]}, ensure_ascii=False, indent=2),
        encoding='utf-8',
    )
    _log(f'저장: {OUTPUT_JSON.name} ({OUTPUT_JSON.stat().st_size:,}B)')


# ---------------------------------------------------------------------------
# 전체 (preprocess부터)
# ---------------------------------------------------------------------------
def run_full_pipeline() -> None:
    from services.analysis_progress import release
    try:
        _run_full_pipeline()
    finally:
        release()


def _run_full_pipeline() -> None:
    import os
    from services.analysis_selection import (prepare_selection, finalize_selection, write_json,
                                             read_csv, write_csv, PREPARED_COLUMNS, empty_clusters, FAQ_MIN_CLUSTER_SIZE)
    policy = get_policy()
    _ensure_logs()
    performance = Performance(DATA_DIR.parent / 'performance.json')
    mode = os.environ.get('TTOBAGI_ANALYSIS_MODE', 'IMPROVEMENT')
    minimum = int(os.environ.get('TTOBAGI_MIN_CLUSTER_SIZE', '5'))
    samples = int(os.environ.get('TTOBAGI_MIN_SAMPLES', '2'))
    faq_minimum = FAQ_MIN_CLUSTER_SIZE
    print('TTOBAGI_PROGRESS:PREPROCESSING', flush=True)
    with performance.stage('CLASSIFYING'):
        targets = prepare_selection(DATA_DIR, mode)
    if not targets:
        write_csv(DATA_DIR / 'preprocessed_full.csv', PREPARED_COLUMNS, [])
        empty_clusters(DATA_DIR, policy)
        performance.skip(['PREPROCESSING', 'EMBEDDING', 'DIMENSION_REDUCTION', 'CLUSTERING',
                          'FAQ_GENERATION', 'RETRIEVING', 'ANSWER_PIPELINE'], 'NO_TARGET_LOGS')
        with performance.stage('FINALIZING'):
            progress('FINALIZING', 0, 1, 'STEP')
            result = finalize_selection(DATA_DIR, min_cluster_size=minimum, min_samples=samples,
                                        faq_min_size=faq_minimum)
            write_json(OUTPUT_JSON, {'faqCandidates': []})
        performance.value['analysisScope'] = result['analysisScope']
        performance.value['resultBytes'] = OUTPUT_JSON.stat().st_size
        performance.save()
        return
    import services.preprocess as PRE
    import services.clustering as CLU
    kb = None

    def cluster_with_faq():
        nonlocal kb
        from services.faq_routing import route_questions
        from services.question_identity import IntentVerifier
        import services.retriever as RET
        with performance.stage('KB_PREFLIGHT'):
            snapshot = DATA_DIR / 'faq_kb_snapshot.json'
            kb = json.loads(snapshot.read_text(encoding='utf-8')) if snapshot.exists() else RET.load_kb()
            if not snapshot.exists():
                write_json(snapshot, kb)
        verifier = IntentVerifier()
        threshold_path = DATA_DIR / 'faq_routing_config.json'
        if threshold_path.exists():
            settings = json.loads(threshold_path.read_text(encoding='utf-8'))
        else:
            settings = dict(threshold=RET.FAQ_MATCH_THRESHOLD, margin=.02)
            write_json(threshold_path, settings)
        version = RET.kb_fingerprint(kb)

        def rank(questions, records):
            if questions:
                verifier.tokenizer = verifier.model = None
            return RET.rank_questions(questions, records)

        def route(texts):
            decisions, active = route_questions(texts, kb, rank, verifier, **settings)
            return decisions, active, verifier, version

        CLU.main(route=route)

    def cluster_questions():
        prepared = DATA_DIR / 'preprocessed_full.csv'
        if prepared.exists() and any(len(row['normalizedForClustering']) >= 2 for row in read_csv(prepared)):
            cluster_with_faq()
        else:
            CLU.main()

    for name, operation, status in (
        ('PREPROCESSING', PRE.main, None),
        ('CLUSTERING', cluster_questions, 'ANALYZING'),
    ):
        _log(f'=== STAGE {name} ===')
        if status:
            print(f'TTOBAGI_PROGRESS:{status}', flush=True)
        started = time.monotonic()
        try:
            with performance.stage(name):
                operation()
        finally:
            # Stage locals are gone; collect cycles before releasing CUDA's cache.
            import gc
            import sys
            gc.collect()
            torch = sys.modules.get('torch')
            if torch is not None and torch.cuda.is_available():
                torch.cuda.empty_cache()
            # stdout preserves this even when the answer pipeline resets RUN_LOG.
            _log(f'=== STAGE {name} elapsed={time.monotonic() - started:.1f}s ===')

    result = finalize_selection(DATA_DIR, min_cluster_size=minimum, min_samples=samples,
                                faq_min_size=faq_minimum)
    if result['completionReason'] != 'RESULTS_GENERATED':
        if not result['analysisScope']['clusteredLogCount']:
            performance.skip(['EMBEDDING', 'DIMENSION_REDUCTION', 'HDBSCAN'], result['completionReason'])
        performance.skip(['FAQ_GENERATION', 'RETRIEVING', 'ANSWER_PIPELINE'], result['completionReason'])
        with performance.stage('FINALIZING'):
            progress('FINALIZING', 0, 1, 'STEP')
            write_json(OUTPUT_JSON, {'faqCandidates': []})
    else:
        import services.faq_generator as FAQ
        import services.retriever as RET
        # Discover bad KB configuration before expensive generation.
        expansions_path = DATA_DIR / 'faq_expansions.json'
        expansions = json.loads(expansions_path.read_text(encoding='utf-8')) if expansions_path.exists() else []
        if kb is None:
            with performance.stage('KB_PREFLIGHT'):
                RET.load_kb()
        for name, operation in [('FAQ_GENERATION', lambda: FAQ.main(exclude_labels={
                                     c['clusterLabel'] for c in expansions})),
                                ('RETRIEVING', lambda: RET.run_for_faqs(kb)),
                                ('ANSWER_PIPELINE', lambda: run_answer_pipeline(expansions))]:
            with performance.stage(name):
                operation()
            import gc
            import sys
            gc.collect()
            torch = sys.modules.get('torch')
            if torch is not None and torch.cuda.is_available():
                torch.cuda.empty_cache()
        progress('FINALIZING', 0, 1, 'STEP')
    performance.value['analysisScope'] = result['analysisScope']
    performance.value['resultBytes'] = OUTPUT_JSON.stat().st_size
    performance.save()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--full', action='store_true',
                    help='preprocess부터 전체 실행 (기본: 답변 파이프라인만)')
    args = ap.parse_args()
    if args.full:
        run_full_pipeline()
    else:
        run_answer_pipeline()


if __name__ == '__main__':
    main()
