"""또타24 재생성 답변 품질 평가기 (E4 = E2 + E3).

E2 (Cross-Encoder): standardQuestion <-> answerDraft 의미 정합 점수
    BAAI/bge-reranker-v2-m3, Sigmoid (0~1)

E3 (LLM-as-judge): 설정된 Llama Judge가 4축 [1-5] 정수로 채점
    - accuracy       : 질문 의도에 정확하게 응답하는가
    - politeness     : 또타 브랜드보이스/공손 표현이 적절한가
    - actionability  : 다음 행동에 대한 구체적 안내가 있는가
    - essential_info : 연락처/링크/필수 정책 정보 등이 포함되는가

needsReview 판정:
    - LLM 4축 중 하나라도 <=2 (분명한 결함)
    - 또는 LLM 평균 < 3.5
    - 또는 (FAQ에 한해) E2 score < 0.20

산출물:
    dataset/faq_candidates_v2.json   (원본에 evaluation 필드 머지, 이전본은 run4.bak으로 백업)
    dataset/answer_eval.jsonl        (per-cluster 체크포인트)
    logs/answer_eval.log             (요약 라인)
    logs/answer_eval_detail.log      (per-cluster prompt + raw json + parsed)
"""
from __future__ import annotations

import json
import os
import re
import shutil
import time
import unicodedata
from pathlib import Path
from services.runtime_config import runtime_path
from services.llm_runtime import model_id, load_chat_model, generate_chat

import numpy as np
import torch
from sentence_transformers import CrossEncoder
from transformers import AutoModelForCausalLM, AutoTokenizer


# ---------------------------------------------------------------------------
# 경로 / 상수
# ---------------------------------------------------------------------------
def _nfd(p: Path) -> Path:
    return Path(unicodedata.normalize('NFD', str(p)))


DATA_DIR = runtime_path('TTOBAGI_DATA_DIR', _nfd(Path(__file__).resolve().parent.parent / 'dataset'))
LOG_DIR = runtime_path('TTOBAGI_LOG_DIR', _nfd(Path(__file__).resolve().parent.parent / 'logs'))

V2_JSON = DATA_DIR / 'faq_candidates_v2.json'
BACKUP_JSON = DATA_DIR / 'faq_candidates_v2.json.run4.bak'
OUTPUT_JSONL = DATA_DIR / 'answer_eval.jsonl'
RUN_LOG = LOG_DIR / 'answer_eval.log'
DETAIL_LOG = LOG_DIR / 'answer_eval_detail.log'

CE_MODEL = 'BAAI/bge-reranker-v2-m3'
LLM_MODEL = model_id('judge')
DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'
CE_DEVICE = os.getenv('TTOBAGI_CE_DEVICE', DEVICE)

LLM_MAX_NEW_TOKENS = 350
LLM_TEMPERATURE = 0.1   # 채점은 결정적으로
LLM_TOP_P = 0.9

E2_THRESHOLD = 0.20     # FAQ에서 이 이하면 needsReview (낮은 의미 정합)
LLM_AXIS_MIN = 2        # 어느 한 축이라도 이하이면 needsReview
LLM_MEAN_MIN = 3.5      # LLM 평균이 이 이하이면 needsReview

DRY_RUN = False


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
        f.write(f'C{cid} [{tag}]\n')
        f.write('=' * 80 + '\n')
        for title, body in sections:
            f.write(f'\n--- {title} ---\n')
            f.write(body.rstrip() + '\n')


# ---------------------------------------------------------------------------
# 데이터 로딩
# ---------------------------------------------------------------------------
def load_v2() -> list[dict]:
    return json.loads(V2_JSON.read_text(encoding='utf-8'))['faqCandidates']


def save_v2(records: list[dict]) -> None:
    payload = {'faqCandidates': records}
    V2_JSON.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                       encoding='utf-8')


# ---------------------------------------------------------------------------
# E2: Cross-Encoder pair score
# ---------------------------------------------------------------------------
def load_ce() -> CrossEncoder:
    _log(f'[E2] Cross-Encoder 로드: {CE_MODEL}')
    return CrossEncoder(
        CE_MODEL,
        max_length=512,
        device=CE_DEVICE,
        activation_fn=torch.nn.Sigmoid(),
    )


def e2_score(ce: CrossEncoder, q: str, a: str) -> float | None:
    if not q or not q.strip():
        return None
    score = ce.predict([(q, a)], show_progress_bar=False).astype(float)
    return round(float(score[0]), 4)


# ---------------------------------------------------------------------------
# E3: LLM-as-judge
# ---------------------------------------------------------------------------
def load_llm() -> tuple[AutoTokenizer, AutoModelForCausalLM]:
    _log(f'[E3] LLM 로드: {LLM_MODEL}')
    return load_chat_model(LLM_MODEL, role='judge')


JUDGE_SYSTEM = (
    'For FAQ accuracy, use ONLY the supplied evidence, not your prior knowledge. '
    'Unsupported specific claims about deadlines, contacts, policies, procedures or services '
    'must receive accuracy <= 2. If FAQ evidence is absent, factual accuracy is unverified; '
    'do not award a high accuracy score. '
    '당신은 서울교통공사 공식 챗봇 "또타24"의 답변 품질을 평가하는 시니어 QA 검수자입니다. '
    '아래 4가지 축에 대해 1~5점 정수로 채점하고 JSON으로만 응답하세요. '
    '점수 기준: 5=매우 우수, 4=양호, 3=보통, 2=결함 있음, 1=심각한 결함.'
)


def build_judge_prompt(cluster: dict) -> str:
    tag = cluster.get('tag', '')
    q = cluster.get('standardQuestion', '').strip() or '(표준질문 없음 — 응급/범위외 클러스터)'
    a = cluster.get('answerDraft', '').strip()
    kws = ', '.join(cluster.get('topKeywords', [])[:5])
    samples = []
    for s in cluster.get('synonyms', [])[:3]:
        samples.append(f'  - {s}')
    sample_block = '\n'.join(samples) if samples else '  (없음)'
    evidence = (cluster.get('retrievalEvidence') or {}).get('used') or []
    evidence_block = '\n\n'.join(
        f'[근거 {i + 1}] {doc["text"]}' for i, doc in enumerate(evidence)
        if isinstance(doc, dict) and isinstance(doc.get('text'), str)
    ) or '(검증 근거 미제공 — FAQ 사실성 확인 불가)'

    axes = (
        '1) accuracy       : 질문 의도/상황에 정확하게 응답하는가 (사실관계 포함)\n'
        '2) politeness     : 또타 브랜드보이스(공손, 안녕/감사, 사과·공감 표현)에 부합하는가\n'
        '3) actionability  : 사용자가 바로 다음 행동을 취할 수 있는 구체적 안내가 있는가\n'
        '4) essential_info : 필요한 연락처/링크/정책/근거 정보가 빠지지 않았는가'
    )

    return (
        f'## 평가 대상\n'
        f'태그: {tag}\n'
        f'표준 질문: {q}\n'
        f'관련 키워드: {kws}\n'
        f'유사 원본 샘플:\n{sample_block}\n\n'
        f'## 사실성 검증에 사용할 검색 근거\n{evidence_block}\n\n'
        f'## 답변 (평가 대상)\n{a}\n\n'
        f'## 채점 축 (각 1~5 정수)\n{axes}\n\n'
        f'## 출력 형식 (JSON 객체 하나만 출력, 다른 텍스트 금지)\n'
        f'{{"accuracy": <int 1-5>, "politeness": <int 1-5>, '
        f'"actionability": <int 1-5>, "essential_info": <int 1-5>, '
        f'"comment": "<한국어 한 줄 코멘트>"}}'
    )


def call_llm(tok, mdl, system: str, user: str) -> str:
    messages = [
        {'role': 'system', 'content': system},
        {'role': 'user', 'content': user},
    ]
    return generate_chat(messages, tok, mdl, max_new_tokens=LLM_MAX_NEW_TOKENS,
                         temperature=LLM_TEMPERATURE, top_p=LLM_TOP_P)


JSON_RE = re.compile(r'\{[^{}]*\}', re.DOTALL)


def parse_judge(raw: str) -> dict | None:
    if not raw:
        return None
    # 가장 그럴듯한 JSON 객체 추출
    candidates = JSON_RE.findall(raw)
    for chunk in candidates:
        try:
            obj = json.loads(chunk)
        except Exception:
            continue
        if not isinstance(obj, dict):
            continue
        keys = {'accuracy', 'politeness', 'actionability', 'essential_info'}
        if not keys.issubset(obj.keys()):
            continue
        cleaned = {}
        for k in keys:
            v = obj[k]
            try:
                iv = int(round(float(v)))
            except Exception:
                return None
            iv = max(1, min(5, iv))
            cleaned[k] = iv
        cleaned['comment'] = str(obj.get('comment', ''))[:200]
        return cleaned
    return None


def llm_judge(tok, mdl, cluster: dict) -> tuple[dict | None, str]:
    prompt = build_judge_prompt(cluster)
    raw = call_llm(tok, mdl, JUDGE_SYSTEM, prompt)
    parsed = parse_judge(raw)
    if parsed is None:
        # 1회 retry: 형식 강조
        retry_prompt = prompt + '\n\n[중요] 위 JSON 형식만 출력하세요. 다른 텍스트나 코드블록 금지.'
        raw2 = call_llm(tok, mdl, JUDGE_SYSTEM, retry_prompt)
        parsed = parse_judge(raw2)
        raw = f'{raw}\n--- RETRY ---\n{raw2}'
    return parsed, raw


# ---------------------------------------------------------------------------
# needsReview 판정
# ---------------------------------------------------------------------------
def decide_review(tag: str, e2: float | None, judge: dict | None) -> tuple[bool, list[str]]:
    reasons: list[str] = []
    if judge is None:
        reasons.append('LLM 판정 파싱 실패')
    else:
        for k in ('accuracy', 'politeness', 'actionability', 'essential_info'):
            if judge[k] <= LLM_AXIS_MIN:
                reasons.append(f'{k} <= {LLM_AXIS_MIN}')
        mean = sum(judge[k] for k in ('accuracy', 'politeness', 'actionability', 'essential_info')) / 4.0
        if mean < LLM_MEAN_MIN:
            reasons.append(f'LLM 평균 < {LLM_MEAN_MIN}')
    if tag == 'FAQ' and e2 is not None and e2 < E2_THRESHOLD:
        reasons.append(f'E2 < {E2_THRESHOLD}')
    return (len(reasons) > 0, reasons)


# ---------------------------------------------------------------------------
# 메인
# ---------------------------------------------------------------------------
def main() -> None:
    _ensure_logs()
    _log(f'평가 시작 — device={DEVICE}')

    # 백업
    if V2_JSON.exists() and not BACKUP_JSON.exists():
        shutil.copy(V2_JSON, BACKUP_JSON)
        _log(f'백업: {BACKUP_JSON.name}')

    records = load_v2()
    _log(f'대상: {len(records)}건')

    ce = load_ce()
    if DRY_RUN:
        tok, mdl = None, None
    else:
        tok, mdl = load_llm()

    OUTPUT_JSONL.write_text('', encoding='utf-8')

    t0 = time.time()
    e2_scores: list[float] = []
    llm_means: list[float] = []
    n_review = 0
    n_parse_fail = 0

    for i, c in enumerate(records, 1):
        cid = c['clusterLabel']
        tag = c.get('tag', '')
        q = c.get('standardQuestion', '').strip()
        a = c.get('answerDraft', '').strip()

        e2 = e2_score(ce, q, a) if a else None
        if e2 is not None:
            e2_scores.append(e2)

        judge_raw = ''
        if DRY_RUN:
            judge = {'accuracy': 0, 'politeness': 0, 'actionability': 0,
                     'essential_info': 0, 'comment': '(dry-run)'}
        else:
            judge, judge_raw = llm_judge(tok, mdl, c)

        if judge is None:
            n_parse_fail += 1
            llm_mean = None
        else:
            llm_mean = round(sum(judge[k] for k in ('accuracy', 'politeness',
                                                   'actionability', 'essential_info')) / 4.0, 2)
            llm_means.append(llm_mean)

        needs, reasons = decide_review(tag, e2, judge)
        if needs:
            n_review += 1

        evaluation = {
            'evaluator': 'v0.1 (E2+E3)',
            'e2_pair_score': e2,
            'llm_axes': judge,
            'llm_mean': llm_mean,
            'needs_review_v2': needs,
            'review_reasons': reasons,
        }
        c['evaluation'] = evaluation

        # 체크포인트 저장
        with OUTPUT_JSONL.open('a', encoding='utf-8') as f:
            f.write(json.dumps({
                'clusterLabel': cid, 'tag': tag, 'evaluation': evaluation,
            }, ensure_ascii=False) + '\n')

        flag = '!!' if needs else '  '
        e2s = f'{e2:.3f}' if e2 is not None else ' -- '
        lms = f'{llm_mean:.2f}' if llm_mean is not None else ' -- '
        _log(f'  [{i:>2}/{len(records)}] C{cid:>2} {flag} [{tag:<13}] E2={e2s} LLM={lms} '
             f'reasons={reasons if needs else ""}')

        _log_detail(cid, tag, [
            ('PROMPT', build_judge_prompt(c)),
            ('RAW LLM OUTPUT', judge_raw),
            ('PARSED', json.dumps(judge, ensure_ascii=False, indent=2)
             if judge else '(parse failed)'),
            ('E2 SCORE', f'{e2}'),
            ('FINAL DECISION', f'needsReview={needs}, reasons={reasons}'),
        ])

    elapsed = time.time() - t0
    _log('')
    _log(f'완료 — 소요 {elapsed:.1f}s')
    if e2_scores:
        _log(f'  E2 평균   = {np.mean(e2_scores):.3f}  median={np.median(e2_scores):.3f}  '
             f'min={min(e2_scores):.3f}  max={max(e2_scores):.3f}')
    if llm_means:
        _log(f'  LLM 평균  = {np.mean(llm_means):.2f}  median={np.median(llm_means):.2f}  '
             f'min={min(llm_means):.2f}  max={max(llm_means):.2f}')
    _log(f'  파싱실패  = {n_parse_fail}건')
    _log(f'  needsReview = {n_review}/{len(records)}건')

    save_v2(records)
    _log(f'저장: {V2_JSON.name} ({V2_JSON.stat().st_size:,}B)')


if __name__ == '__main__':
    main()
