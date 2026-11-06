"""OUT_OF_SCOPE 재생성 v0.2 — 사전 케이스 분류 + 케이스별 단일 가이드 주입.

입력:
  dataset/faq_candidates_v3.json   (v3 baseline)
  dataset/answer_templates.json    (cases 서브키 포함, v0.4)
  dataset/clusters.csv             (분류용 원본 샘플)

출력:
  dataset/faq_candidates_v5.json   (OOS 12건 갱신, 69건 v3 그대로)
  dataset/oos_refresh.jsonl
  logs/oos_refresh.log
  logs/oos_refresh_detail.log
"""
from __future__ import annotations

import copy
import json
import time
import unicodedata
from pathlib import Path
from services.runtime_config import runtime_path

import numpy as np
import pandas as pd

import services.answer_regenerator as AR
import services.answer_evaluator as AE


def _nfd(p):
    return Path(unicodedata.normalize('NFD', str(p)))


DATA_DIR = runtime_path('TTOBAGI_DATA_DIR', _nfd(Path(__file__).resolve().parent.parent / 'dataset'))
LOG_DIR = runtime_path('TTOBAGI_LOG_DIR', _nfd(Path(__file__).resolve().parent.parent / 'logs'))

V3_JSON = DATA_DIR / 'faq_candidates_v3.json'
V5_JSON = DATA_DIR / 'faq_candidates_v5.json'
CLUSTERS_CSV = DATA_DIR / 'clusters.csv'
OUTPUT_JSONL = DATA_DIR / 'oos_refresh.jsonl'
RUN_LOG = LOG_DIR / 'oos_refresh.log'
DETAIL_LOG = LOG_DIR / 'oos_refresh_detail.log'

N_ATTEMPTS = 3

# ---------------------------------------------------------------------------
# 케이스 분류
# ---------------------------------------------------------------------------
CORPORATE = [
    '조직도', '기업', '경영', '공시', '통계', '재무', '보수', '보고서', '보도',
    '직원', '인사', '채용', '합격', '필기', '발표', '부서', '담당부서', '자문',
    'esg', '연차', '보수내규', '근무', '취업', '경력', '자격', '자기소개서',
    '총무', '면접', '상담', '민원', '민뤈', '이용내역', '시민', '임원',
]
COMPLAINT = [
    '짜증', '화나', '미친', '새끼', '좆', '쳐', '죽이', '꺼져', '병신', '쌍', '꺼지',
    '욕', '세금', '아깝', '귀찮', '현기증', '퇴사', '개짜증', '악',
    '좇', '시발', '씨발', '존나', '꼴리',
]
EXTERNAL = [
    '코레일', 'KTX', 'ITX', '공항철도', '아렉스', '시내버스',
    '다산120', '다산콜', '120', 'korail', 'airport',
]


def classify_oos(topkw: list, samples: list) -> tuple[str, list[str]]:
    txt = ' '.join(topkw + samples)
    txt_low = txt.lower()
    hits_ext  = [p for p in EXTERNAL  if p.lower() in txt_low]
    hits_comp = [p for p in COMPLAINT if p in txt]
    hits_corp = [p for p in CORPORATE if p.lower() in txt_low]
    if hits_ext:  return 'external_agency',   hits_ext
    if hits_comp: return 'complaint_emotion', hits_comp
    if hits_corp: return 'corporate_info',    hits_corp
    return 'ambiguous', []


# ---------------------------------------------------------------------------
# 로그
# ---------------------------------------------------------------------------
def _ensure_logs() -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    for p in (RUN_LOG, DETAIL_LOG):
        p.write_text('', encoding='utf-8')


def _log(msg: str) -> None:
    print(msg, flush=True)
    with RUN_LOG.open('a', encoding='utf-8') as f:
        f.write(msg + '\n')


def _log_detail(cid, sections):
    with DETAIL_LOG.open('a', encoding='utf-8') as f:
        f.write('\n' + '=' * 80 + '\n')
        f.write(f'C{cid} OOS REFRESH v0.2\n')
        f.write('=' * 80 + '\n')
        for title, body in sections:
            f.write(f'\n--- {title} ---\n')
            f.write((body or '').rstrip() + '\n')


# ---------------------------------------------------------------------------
# 메인
# ---------------------------------------------------------------------------
def main() -> None:
    _ensure_logs()
    _log(f'OOS refresh v0.2 시작 — device={AE.DEVICE}')

    v3 = json.loads(V3_JSON.read_text(encoding='utf-8'))['faqCandidates']
    templates = AR.load_templates()
    shared = templates['_shared']
    AR.templates_meta = templates['_meta']
    retrieval = AR.load_retrieval()
    clu_df = pd.read_csv(CLUSTERS_CSV, encoding='utf-8-sig')

    targets = [c for c in v3 if c.get('tag') == 'OUT_OF_SCOPE']
    _log(f'전체 {len(v3)}건 중 OUT_OF_SCOPE {len(targets)}건 대상')

    # 케이스 분포 사전 점검
    case_dist = {}
    case_map = {}
    for c in targets:
        cid = c['clusterLabel']
        topkw = c.get('topKeywords', [])[:8]
        samples = clu_df[clu_df['clusterId'] == cid]['text'].head(10).tolist()
        case, hits = classify_oos(topkw, samples)
        case_map[cid] = (case, hits)
        case_dist[case] = case_dist.get(case, 0) + 1
    _log(f'케이스 분포: {case_dist}')
    for cid, (case, hits) in sorted(case_map.items()):
        _log(f'  C{cid:>2} → {case:<18} (hits={hits[:3]})')

    from services.llm_runtime import load_pipeline_models
    tok, mdl, judge_tok, judge_mdl = load_pipeline_models(AR.load_model, AE.load_llm)
    ce = AE.load_ce()

    OUTPUT_JSONL.write_text('', encoding='utf-8')

    new_map = {}
    t0 = time.time()
    tag_cfg_base = templates['OUT_OF_SCOPE']

    for i, c in enumerate(targets, 1):
        cid = c['clusterLabel']
        case_key, _ = case_map[cid]
        prev_a = c.get('answerDraft', '').strip()
        prev_eval = c.get('evaluation', {})
        prev_mean = prev_eval.get('llm_mean')

        # oos_case 주입한 사본
        cluster_for_gen = {**c, 'oos_case': case_key}
        kb_chunks = AR.select_kb_chunks(retrieval.get(cid))
        prompt = AR.build_prompt(cluster_for_gen, tag_cfg_base, shared, kb_chunks)

        attempts = []
        last_raw = ''
        for _ in range(N_ATTEMPTS):
            raw = AR.generate(prompt, tok, mdl)
            body = AR.clean_body(raw)
            ok, missing = AR.validate_answer(body, 'OUT_OF_SCOPE')
            # 케이스별 금지 단어 검증 추가
            case_cfg = tag_cfg_base['cases'][case_key]
            for fb in case_cfg.get('forbidden_mentions', []):
                if fb in body:
                    missing.append(f'금지단어 포함: {fb}')
            attempts.append((body, missing))
            last_raw = raw
            if not missing:
                break
        best_body, best_missing = min(attempts, key=lambda x: (len(x[1]), -len(x[0])))

        e2 = AE.e2_score(ce, c.get('standardQuestion', ''), best_body) \
            if c.get('standardQuestion', '').strip() else None
        cluster_for_eval = {**c, 'answerDraft': best_body}
        judge, judge_raw = AE.llm_judge(judge_tok, judge_mdl, cluster_for_eval)
        llm_mean = (
            round(sum(judge[k] for k in ('accuracy', 'politeness',
                                         'actionability', 'essential_info')) / 4.0, 2)
            if judge else None
        )
        needs, reasons = AE.decide_review('OUT_OF_SCOPE', e2, judge)

        new_record = copy.deepcopy(c)
        new_record['answerDraft'] = best_body
        new_record['oos_case'] = case_key
        new_record['refinement'] = {
            'refiner': 'v0.3 (oos_refresher v0.2)',
            'template_version': 'v0.4',
            'oos_case': case_key,
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
        if 'answerMeta' in new_record:
            new_record['answerMeta']['regenerator'] = 'v0.3 + oos_refresher v0.2'
            new_record['answerMeta']['attempts'] = len(attempts)
            new_record['answerMeta']['validationRetried'] = len(attempts) > 1
            new_record['answerMeta']['validationMissing'] = best_missing
            new_record['answerMeta']['oosCase'] = case_key
        new_map[cid] = new_record

        with OUTPUT_JSONL.open('a', encoding='utf-8') as f:
            f.write(json.dumps({
                'clusterLabel': cid, 'oos_case': case_key,
                'refinement': new_record['refinement'],
                'evaluation': new_record['evaluation'],
            }, ensure_ascii=False) + '\n')

        delta = (llm_mean - prev_mean) if (llm_mean is not None and prev_mean is not None) else None
        delta_s = f'Δ={delta:+.2f}' if delta is not None else 'Δ=-'
        flag = 'ok' if not needs else '!!'
        _log(f'  [{i:>2}/{len(targets)}] C{cid:>2} case={case_key:<18} '
             f'{prev_mean}->{llm_mean} {delta_s} attempts={len(attempts)} {flag}')

        _log_detail(cid, [
            ('OOS CASE', case_key),
            ('TOP KW', ', '.join(c.get('topKeywords', [])[:8])),
            ('SAMPLES (cluster)', '\n'.join(clu_df[clu_df['clusterId'] == cid]['text'].head(8).tolist())),
            ('PREV ANSWER', prev_a),
            ('PREV EVAL', json.dumps(prev_eval, ensure_ascii=False, indent=2)),
            ('PROMPT', prompt),
            ('LLM RAW (last)', last_raw),
            ('NEW ANSWER (best)', best_body),
            ('NEW EVAL', json.dumps({
                'e2': e2, 'judge': judge, 'mean': llm_mean,
                'needs_review': needs, 'reasons': reasons,
            }, ensure_ascii=False, indent=2)),
            ('JUDGE RAW', judge_raw),
        ])

    elapsed = time.time() - t0
    _log('')
    _log(f'완료 — 소요 {elapsed:.1f}s')

    v5 = [new_map.get(c['clusterLabel'], c) for c in v3]
    V5_JSON.write_text(json.dumps({'faqCandidates': v5}, ensure_ascii=False, indent=2),
                       encoding='utf-8')
    _log(f'저장: {V5_JSON.name} ({V5_JSON.stat().st_size:,}B, {len(v5)}건)')

    means_b, means_a = [], []
    pass_now = 0
    for cid, rec in new_map.items():
        b = next(c for c in v3 if c['clusterLabel'] == cid)
        mb = b.get('evaluation', {}).get('llm_mean')
        ma = rec.get('evaluation', {}).get('llm_mean')
        if mb is not None: means_b.append(mb)
        if ma is not None: means_a.append(ma)
        if not rec['evaluation'].get('needs_review_v2'): pass_now += 1
    _log('')
    _log('=== OOS 12건 before/after (v3 → v5) ===')
    _log(f'  LLM mean  before: {np.mean(means_b):.2f}  after: {np.mean(means_a):.2f}  '
         f'Δ={np.mean(means_a)-np.mean(means_b):+.2f}')
    _log(f'  needs_review 통과: {pass_now}/{len(new_map)}건')

    total_nr_v3 = sum(1 for c in v3 if c['evaluation'].get('needs_review_v2'))
    total_nr_v5 = sum(1 for c in v5 if c['evaluation'].get('needs_review_v2'))
    _log(f'  전체 needsReview: v3 {total_nr_v3}/81 → v5 {total_nr_v5}/81')


if __name__ == '__main__':
    main()
