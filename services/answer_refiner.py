"""또타24 답변 정제기 (A1-b) — needsReview 클러스터만 표준질문+답변 재생성 후 즉시 재평가.

입력:
  dataset/faq_candidates_v2.json   (evaluation 필드 포함)
  dataset/faq_retrieval.json
  dataset/answer_templates.json

출력:
  dataset/faq_candidates_v3.json   (15건 refine + 66건 v2 그대로 복사)
  dataset/answer_refine.jsonl      (refine된 15건 체크포인트)
  logs/answer_refine.log           (요약)
  logs/answer_refine_detail.log    (per-cluster prompt/output/before-after)

설계:
  1) 표준질문에 한자(중국어) 포함이면 LLM로 한국어 재작성 (의미 보존)
  2) 답변 재생성: build_prompt 기반 + 이전 답변 + LLM 평가 코멘트 + 약점 축 강조
  3) best-of-3 + brand-voice validation
  4) 새 답변에 대해 E2 + LLM 4축 즉시 평가
  5) v2 → v3 비교 가능한 형태로 머지 (refinement, evaluation 필드)
"""
from __future__ import annotations

import copy
import json
import re
import shutil
import time
import unicodedata
from pathlib import Path
from services.runtime_config import runtime_path

import numpy as np
import torch
from sentence_transformers import CrossEncoder
from transformers import AutoModelForCausalLM, AutoTokenizer

# 기존 모듈 재사용 (LLM/CE는 본 모듈에서 한 번만 로드해서 함수에 주입)
import services.answer_regenerator as AR
import services.answer_evaluator as AE


# ---------------------------------------------------------------------------
# 경로
# ---------------------------------------------------------------------------
def _nfd(p: Path) -> Path:
    return Path(unicodedata.normalize('NFD', str(p)))


DATA_DIR = runtime_path('TTOBAGI_DATA_DIR', _nfd(Path(__file__).resolve().parent.parent / 'dataset'))
LOG_DIR = runtime_path('TTOBAGI_LOG_DIR', _nfd(Path(__file__).resolve().parent.parent / 'logs'))

V2_JSON = DATA_DIR / 'faq_candidates_v2.json'
V3_JSON = DATA_DIR / 'faq_candidates_v3.json'
RETRIEVAL_IN = DATA_DIR / 'faq_retrieval.json'
TEMPLATES_IN = DATA_DIR / 'answer_templates.json'
OUTPUT_JSONL = DATA_DIR / 'answer_refine.jsonl'

RUN_LOG = LOG_DIR / 'answer_refine.log'
DETAIL_LOG = LOG_DIR / 'answer_refine_detail.log'

# Refine 전용 하이퍼
N_ATTEMPTS = 3              # best-of-N
HANJA_RE = re.compile(r'[一-鿿ぁ-んァ-ン]')  # 한자/일본어 검출


# ---------------------------------------------------------------------------
# 로깅
# ---------------------------------------------------------------------------
def _ensure_logs() -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    for p in (RUN_LOG, DETAIL_LOG):
        p.write_text('', encoding='utf-8')


def _log(msg: str) -> None:
    print(msg, flush=True)
    with RUN_LOG.open('a', encoding='utf-8') as f:
        f.write(msg + '\n')


def _log_detail(cid: int, tag: str, sections: list[tuple[str, str]]) -> None:
    with DETAIL_LOG.open('a', encoding='utf-8') as f:
        f.write('\n' + '=' * 80 + '\n')
        f.write(f'C{cid} [{tag}] REFINE\n')
        f.write('=' * 80 + '\n')
        for title, body in sections:
            f.write(f'\n--- {title} ---\n')
            f.write((body or '').rstrip() + '\n')


# ---------------------------------------------------------------------------
# 표준질문 한국어 재작성
# ---------------------------------------------------------------------------
QUESTION_SYSTEM = (
    '당신은 서울교통공사 챗봇 또타24의 FAQ 표준질문을 다듬는 편집자입니다. '
    '주어진 표준질문을 한국어로만 자연스럽게 재작성하세요. 의미는 유지하되, '
    '한자, 중국어, 일본어는 모두 한국어로 바꾸세요. 출력은 표준질문 한 문장만.'
)


def has_foreign(text: str) -> bool:
    return bool(HANJA_RE.search(text or ''))


def refine_question(tok, mdl, prev_q: str, samples: list[str],
                    topkw: list[str]) -> str:
    sample_block = '\n'.join(f'  - {s}' for s in samples[:5]) or '  (없음)'
    user = (
        f'기존 표준질문 (한자/외국어 포함): {prev_q}\n\n'
        f'클러스터 원본 샘플 (한국어):\n{sample_block}\n\n'
        f'관련 키워드: {", ".join(topkw[:5])}\n\n'
        f'재작성된 표준질문 (한국어 한 문장만, 다른 설명 없이):'
    )
    raw = AE.call_llm(tok, mdl, QUESTION_SYSTEM, user)
    # 첫 줄만 채택
    line = (raw.strip().split('\n')[0] or '').strip()
    # 따옴표 제거
    line = line.strip('"').strip("'").strip('「』').strip()
    # 한자가 또 섞이면 그래도 그대로 채택 (fallback)
    return line or prev_q


# ---------------------------------------------------------------------------
# 답변 재생성 프롬프트 (이전 답변 + 평가 피드백 포함)
# ---------------------------------------------------------------------------
def build_refine_prompt(cluster: dict, tag_cfg: dict, shared: dict,
                        kb_chunks: list[dict], prev_answer: str,
                        eval_axes: dict | None,
                        review_reasons: list[str]) -> str:
    """answer_regenerator의 build_prompt를 베이스로 사용하고 refine 컨텍스트 추가."""
    base = AR.build_prompt(cluster, tag_cfg, shared, kb_chunks)

    weak_axes = []
    if eval_axes:
        for k in ('accuracy', 'politeness', 'actionability', 'essential_info'):
            v = eval_axes.get(k, 5)
            if v <= 3:
                weak_axes.append(f'- {k} = {v}/5')
    weak_block = '\n'.join(weak_axes) if weak_axes else '- (특정 약점 없음)'

    comment = (eval_axes or {}).get('comment', '').strip() or '(코멘트 없음)'
    reasons = ', '.join(review_reasons) if review_reasons else '(없음)'

    feedback = (
        '\n\n[REFINE 컨텍스트 — 반드시 반영]\n'
        '이전 답변과 평가 코멘트는 사실의 근거가 아닙니다. 위 검색 근거와 대조하여 '
        '확인되지 않는 시간·연락처·정책·절차·약속은 삭제하고 새 사실을 추가하지 마세요.\n'
        f'(1) 이전 답변:\n{prev_answer}\n\n'
        f'(2) 이전 답변의 평가 코멘트: {comment}\n\n'
        f'(3) 보완이 필요한 축 (1~5 점수):\n{weak_block}\n\n'
        f'(4) needs_review 사유: {reasons}\n\n'
        '[지시]\n'
        '- 이전 답변의 약점을 위 축 기준으로 직접 해결할 것\n'
        '- 행동 안내는 검색 근거 또는 승인된 고정 안내에 명시된 내용만 사용할 것\n'
        '- 연락처/링크/시간이 근거에 없으면 추가하지 말고 확인 불가로 안내\n'
        '- accuracy가 낮으면 표준질문 의도에 직접 응답\n'
        '- 한국어로만 답변 (한자/중국어/일본어 금지)\n'
        '- 답변 한 통을 자연스럽게 통합하여 출력 (인사~마무리)\n'
    )
    contract = AR.GA.read_contract(base)
    contract['review_feedback'] = review_reasons
    return AR.GA.append_contract(base + feedback, contract)


# ---------------------------------------------------------------------------
# 메인
# ---------------------------------------------------------------------------
def main() -> None:
    _ensure_logs()
    _log(f'정제 시작 — device={AE.DEVICE}')

    # 데이터 로드
    v2 = json.loads(V2_JSON.read_text(encoding='utf-8'))['faqCandidates']
    retrieval = AR.load_retrieval()
    templates = AR.load_templates()
    shared = templates['_shared']
    AR.templates_meta = templates['_meta']  # 전역 주입 (build_prompt 의존)
    _log(f'전체 {len(v2)}건 로드')

    targets = [c for c in v2 if c.get('evaluation', {}).get('needs_review_v2')]
    _log(f'refine 대상 (needsReview): {len(targets)}건')

    # 모델 로드 (1회)
    from services.llm_runtime import load_pipeline_models
    tok, mdl, judge_tok, judge_mdl = load_pipeline_models(AR.load_model, AE.load_llm)
    ce = AE.load_ce()

    OUTPUT_JSONL.write_text('', encoding='utf-8')

    # cid → 새 레코드
    new_map: dict[int, dict] = {}

    t0 = time.time()
    for i, c in enumerate(targets, 1):
        cid = c['clusterLabel']
        tag = c['tag']
        prev_q = c.get('standardQuestion', '').strip()
        prev_a = c.get('answerDraft', '').strip()
        prev_eval = c.get('evaluation', {})
        prev_axes = prev_eval.get('llm_axes') or {}
        prev_reasons = prev_eval.get('review_reasons', [])
        synonyms = c.get('synonyms', [])
        topkw = c.get('topKeywords', [])

        # 1) 표준질문 정제 (한자 포함 시만)
        q_refined = False
        if has_foreign(prev_q):
            new_q = refine_question(tok, mdl, prev_q, synonyms, topkw)
            q_refined = True
        else:
            new_q = prev_q

        # 2) 답변 재생성 (best-of-N)
        cluster_for_gen = {**c, 'standardQuestion': new_q}
        tag_cfg = templates[tag]
        kb_chunks = AR.select_kb_chunks(retrieval.get(cid))
        prompt = build_refine_prompt(
            cluster_for_gen, tag_cfg, shared, kb_chunks,
            prev_a, prev_axes, prev_reasons,
        )

        attempts: list[tuple[str, list[str]]] = []
        last_raw = ''
        for attempt_i in range(N_ATTEMPTS):
            raw = AR.generate(prompt, tok, mdl)
            body = AR.clean_body(raw)
            ok, missing = AR.validate_answer(body, tag)
            attempts.append((body, missing))
            last_raw = raw
            if not missing:
                break
        # best: fewest missing, longest body tiebreaker
        best_body, best_missing = min(attempts, key=lambda x: (len(x[1]), -len(x[0])))

        # 3) 새 답변 평가
        e2 = AE.e2_score(ce, new_q, best_body) if new_q else None
        cluster_for_eval = {**c, 'standardQuestion': new_q, 'answerDraft': best_body}
        judge, judge_raw = AE.llm_judge(judge_tok, judge_mdl, cluster_for_eval)
        llm_mean = (
            round(sum(judge[k] for k in ('accuracy', 'politeness',
                                         'actionability', 'essential_info')) / 4.0, 2)
            if judge else None
        )
        needs, reasons = AE.decide_review(tag, e2, judge)

        # 4) 새 레코드 구성
        new_record = copy.deepcopy(c)
        new_record['standardQuestion'] = new_q
        new_record['answerDraft'] = best_body
        new_record['refinement'] = {
            'refiner': 'v0.1 (A1-b)',
            'question_refined': q_refined,
            'prev_standardQuestion': prev_q if q_refined else None,
            'prev_answerDraft': prev_a,
            'prev_evaluation': prev_eval,
            'attempts': len(attempts),
            'final_missing': best_missing,
        }
        new_record['evaluation'] = {
            'evaluator': 'v0.1 (E2+E3)',
            'e2_pair_score': e2,
            'llm_axes': judge,
            'llm_mean': llm_mean,
            'needs_review_v2': needs,
            'review_reasons': reasons,
        }
        # answerMeta도 refresh
        if 'answerMeta' in new_record:
            new_record['answerMeta']['regenerator'] = 'v0.3 + refiner v0.1'
            new_record['answerMeta']['attempts'] = len(attempts)
            new_record['answerMeta']['validationRetried'] = len(attempts) > 1
            new_record['answerMeta']['validationMissing'] = best_missing

        new_map[cid] = new_record

        # 체크포인트
        with OUTPUT_JSONL.open('a', encoding='utf-8') as f:
            f.write(json.dumps({
                'clusterLabel': cid, 'tag': tag,
                'refinement': new_record['refinement'],
                'evaluation': new_record['evaluation'],
            }, ensure_ascii=False) + '\n')

        prev_mean = prev_eval.get('llm_mean')
        delta = (llm_mean - prev_mean) if (llm_mean is not None and prev_mean is not None) else None
        flag = '!!' if needs else 'ok'
        delta_s = f'Δ={delta:+.2f}' if delta is not None else 'Δ=-'
        e2_s = f'{e2:.3f}' if e2 is not None else ' --'
        _log(f'  [{i:>2}/{len(targets)}] C{cid:>2} [{tag:<13}] '
             f'{prev_mean}->{llm_mean} {delta_s} E2={e2_s} attempts={len(attempts)} '
             f'{flag} qRefined={q_refined}')

        _log_detail(cid, tag, [
            ('PREV STANDARD Q', prev_q),
            ('NEW STANDARD Q', new_q + (' (refined)' if q_refined else '')),
            ('PREV ANSWER', prev_a),
            ('PREV EVAL', json.dumps(prev_eval, ensure_ascii=False, indent=2)),
            ('REFINE PROMPT', prompt),
            ('LLM RAW (last attempt)', last_raw),
            ('NEW ANSWER (best)', best_body),
            ('NEW EVAL', json.dumps({
                'e2': e2, 'judge': judge, 'mean': llm_mean,
                'needs_review': needs, 'reasons': reasons,
            }, ensure_ascii=False, indent=2)),
            ('JUDGE RAW', judge_raw),
        ])

    elapsed = time.time() - t0
    _log('')
    _log(f'정제 완료 — 소요 {elapsed:.1f}s')

    # 5) v3 머지: refine된 cid만 새 레코드, 나머지는 v2 그대로
    v3_records = []
    for c in v2:
        cid = c['clusterLabel']
        v3_records.append(new_map.get(cid, c))

    V3_JSON.write_text(
        json.dumps({'faqCandidates': v3_records}, ensure_ascii=False, indent=2),
        encoding='utf-8',
    )
    _log(f'저장: {V3_JSON.name} ({V3_JSON.stat().st_size:,}B, {len(v3_records)}건)')

    # 6) before/after 요약
    _log('')
    _log('=== before/after 요약 (refined 15건) ===')
    means_before, means_after = [], []
    n_pass_now = 0
    for cid, rec in new_map.items():
        b = next(c for c in v2 if c['clusterLabel'] == cid)
        mb = b.get('evaluation', {}).get('llm_mean')
        ma = rec.get('evaluation', {}).get('llm_mean')
        if mb is not None: means_before.append(mb)
        if ma is not None: means_after.append(ma)
        if rec.get('evaluation', {}).get('needs_review_v2') is False:
            n_pass_now += 1
    _log(f'  LLM mean  before: {np.mean(means_before):.2f}   after: {np.mean(means_after):.2f}   '
         f'Δ={np.mean(means_after)-np.mean(means_before):+.2f}')
    _log(f'  needs_review 해제: {n_pass_now}/{len(new_map)}건')


if __name__ == '__main__':
    main()
